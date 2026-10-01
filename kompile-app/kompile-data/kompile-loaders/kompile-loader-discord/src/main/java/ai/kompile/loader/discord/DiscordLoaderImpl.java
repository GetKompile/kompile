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
import ai.kompile.utils.MapUtils;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.loader.discord.DiscordModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 *   <li>{@code maxMessages} - TOTAL max messages across all channels and threads (default 0 = unlimited)</li>
 *   <li>{@code startDate} - ISO date to start from (overrides daysBack)</li>
 *   <li>{@code endDate} - ISO date to end at</li>
 *   <li>{@code since} - ISO-8601 instant lower bound; combined with startDate/daysBack using
 *       whichever bound is later</li>
 *   <li>{@code attachmentDirectory} - when set, attachment originals are saved under it and
 *       described via {@code "attachments"} message metadata instead of being downloaded per-run</li>
 *   <li>{@code maxAttachmentBytes} - skip saving an attachment larger than this (default 25MB)</li>
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
        int maxMessages = MapUtils.toInt(meta.get("maxMessages"), 0);
        int daysBack = MapUtils.toInt(meta.get("daysBack"), 30);
        Path attachmentDirectory = resolveAttachmentDirectory(meta);
        long maxAttachmentBytes = MapUtils.toLong(meta.get("maxAttachmentBytes"),
                DiscordAttachmentStorage.DEFAULT_MAX_ATTACHMENT_BYTES);

        // Calculate time bounds. B4/B7/C3: computeEffectiveSince resolves "since" folded with
        // startDate/daysBack, using whichever bound is later. Message pagination (afterSnowflake,
        // via computeAfterSnowflake) and archived-thread discovery below share this one
        // resolution — using the raw "since" alone for thread discovery would mean a server with
        // no explicit "since" (the common case) pages through every archived thread ever created,
        // one request per thread, only to have every one of its messages filtered out by the
        // default 30-day daysBack bound.
        Instant effectiveSince = computeEffectiveSince(meta, daysBack);
        String afterSnowflake = computeAfterSnowflake(meta, daysBack);
        String beforeSnowflake = computeBeforeSnowflake(meta);

        Duration rateLimitDelay = Duration.ofMillis(MapUtils.toInt(meta.get("rateLimitDelayMs"), 500));
        DiscordApiService api = createApiService(botToken, rateLimitDelay);

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

        Set<String> targetChannelIds = new HashSet<>();
        for (Channel ch : targetChannels) targetChannelIds.add(ch.id());

        // Collect threads if requested
        List<Channel> threads = new ArrayList<>();
        if (includeThreads) {
            // B3: the guild-wide active-threads endpoint returns threads from every channel in
            // the guild, not just the ones targeted by channelIds — keep only threads parented
            // under a targeted channel. B2: a guild-wide failure here must only drop active
            // threads, not abort the whole load.
            try {
                for (Channel candidate : api.getActiveThreads(guildId)) {
                    if (candidate.parentId() != null && targetChannelIds.contains(candidate.parentId())) {
                        threads.add(candidate);
                    }
                }
            } catch (DiscordForbiddenException e) {
                log.debug("Skipping active-thread discovery for guild {} (no access): {}", guildId, e.getMessage());
            } catch (Exception e) {
                log.warn("Failed to list active threads for guild {}: {}", guildId, e.getMessage());
            }
            for (Channel ch : targetChannels) {
                // B2: archived-thread discovery is per-channel. Discord 403s
                // /threads/archived/public for a channel missing READ_MESSAGE_HISTORY or
                // VIEW_CHANNEL, and the guild channel list can include such channels — one
                // inaccessible channel must only drop that channel's threads, never abort the
                // whole load before any messages are fetched.
                try {
                    threads.addAll(api.getArchivedPublicThreads(ch.id(), effectiveSince));
                } catch (DiscordForbiddenException e) {
                    log.debug("Skipping archived public threads for #{} (no access): {}", ch.name(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to list archived public threads for #{}: {}", ch.name(), e.getMessage());
                }
                try {
                    threads.addAll(api.getArchivedPrivateThreads(ch.id(), effectiveSince));
                } catch (DiscordForbiddenException e) {
                    log.debug("Skipping archived private threads for #{} (no access): {}", ch.name(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to list archived private threads for #{}: {}", ch.name(), e.getMessage());
                }
            }
            log.info("Found {} threads to load", threads.size());
        }

        List<Document> documents = new ArrayList<>();
        int totalChannels = targetChannels.size() + threads.size();
        int processedChannels = 0;

        // B6: maxMessages is a TOTAL cap across channels and threads, not a per-channel cap.
        // <= 0 means unlimited, in which case remainingBudget is never consulted and 0 keeps
        // being passed through as "unlimited" to each fetch.
        boolean unlimited = maxMessages <= 0;
        int remainingBudget = maxMessages;

        // B2: isolate failures per channel (403 is a skip, not a failure); only abort loading if
        // every targeted channel that wasn't access-skipped also failed.
        int channelAttempts = 0;
        int channelFailures = 0;
        Exception firstChannelFailure = null;

        // Load messages from each channel
        for (Channel channel : targetChannels) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!unlimited && remainingBudget <= 0) break;
            processedChannels++;
            int pct = 10 + (80 * processedChannels / Math.max(totalChannels, 1));
            notifyProgress(progressCallback, "Loading messages", pct,
                    "Channel: #" + channel.name() + " (" + processedChannels + "/" + totalChannels + ")");

            int fetchLimit = unlimited ? 0 : remainingBudget;
            List<Message> messages;
            try {
                messages = api.getChannelMessages(channel.id(), fetchLimit, afterSnowflake, beforeSnowflake);
            } catch (DiscordForbiddenException e) {
                log.warn("Skipping #{} (no access): {}", channel.name(), e.getMessage());
                continue;
            } catch (Exception e) {
                channelAttempts++;
                channelFailures++;
                if (firstChannelFailure == null) firstChannelFailure = e;
                log.warn("Failed to load messages from #{}: {}", channel.name(), e.getMessage());
                continue;
            }
            channelAttempts++;
            log.info("Loaded {} messages from #{}", messages.size(), channel.name());
            if (!unlimited) remainingBudget -= messages.size();

            for (Message msg : messages) {
                Document doc = convertMessageToDocument(msg, channel, guild, sourceDescriptor, includeAttachments, roleMap);
                if (includeAttachments && attachmentDirectory != null
                        && msg.attachments() != null && !msg.attachments().isEmpty()) {
                    applyAttachmentStorage(api, doc, msg, attachmentDirectory, maxAttachmentBytes);
                }
                documents.add(doc);
            }
        }

        if (channelAttempts > 0 && channelFailures == channelAttempts) {
            throw new IOException("All " + channelAttempts + " targeted Discord channel(s) failed",
                    firstChannelFailure);
        }

        // Load messages from threads
        for (Channel thread : threads) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!unlimited && remainingBudget <= 0) break;
            processedChannels++;
            int pct = 10 + (80 * processedChannels / Math.max(totalChannels, 1));
            notifyProgress(progressCallback, "Loading threads", pct,
                    "Thread: " + thread.name() + " (" + processedChannels + "/" + totalChannels + ")");

            int fetchLimit = unlimited ? 0 : remainingBudget;
            List<Message> messages;
            try {
                messages = api.getChannelMessages(thread.id(), fetchLimit, afterSnowflake, beforeSnowflake);
            } catch (DiscordForbiddenException e) {
                log.warn("Skipping thread '{}' (no access): {}", thread.name(), e.getMessage());
                continue;
            } catch (Exception e) {
                log.warn("Failed to load messages from thread '{}': {}", thread.name(), e.getMessage());
                continue;
            }
            log.info("Loaded {} messages from thread '{}'", messages.size(), thread.name());
            if (!unlimited) remainingBudget -= messages.size();

            for (Message msg : messages) {
                Document doc = convertMessageToDocument(msg, thread, guild, sourceDescriptor, includeAttachments, roleMap);
                if (includeAttachments && attachmentDirectory != null
                        && msg.attachments() != null && !msg.attachments().isEmpty()) {
                    applyAttachmentStorage(api, doc, msg, attachmentDirectory, maxAttachmentBytes);
                }
                documents.add(doc);
            }
        }

        notifyProgress(progressCallback, "Complete", 100,
                "Loaded " + documents.size() + " messages from " + guild.name());
        log.info("Discord loading complete: {} documents from server '{}'", documents.size(), guild.name());
        return documents;
    }

    /**
     * Test seam: overridden by tests to point the internal {@link DiscordApiService} at a fake
     * HTTP server instead of the real Discord API, the same pattern {@link DiscordApiService}
     * itself uses for its own tests.
     */
    DiscordApiService createApiService(String botToken, Duration rateLimitDelay) {
        return new DiscordApiService(botToken, rateLimitDelay);
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

        // Core identifiers (C1: "discord://<guildId>/<channelId>/<messageId>")
        String sourcePath = "discord://" + guild.id() + "/" + channel.id() + "/" + msg.id();
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

    /**
     * C4/B5: when an attachmentDirectory is configured, downloads and saves each attachment's
     * original bytes under it (content-addressed by the message's source_path) and records an
     * {@code "attachments"} metadata entry describing what was saved or skipped. No separate
     * attachment Document is produced and no text is extracted in this mode.
     */
    private void applyAttachmentStorage(DiscordApiService api, Document doc, Message msg,
                                         Path attachmentDirectory, long maxAttachmentBytes) {
        String sourcePath = (String) doc.getMetadata().get(GraphConstants.META_SOURCE_PATH);
        String messageKey = DiscordAttachmentStorage.messageKey(sourcePath);
        Set<String> usedFileNames = new HashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Attachment att : msg.attachments()) {
            entries.add(DiscordAttachmentStorage.save(
                    api, att, attachmentDirectory, messageKey, maxAttachmentBytes, usedFileNames));
        }
        doc.getMetadata().put("attachments", entries);
    }

    private static Path resolveAttachmentDirectory(Map<String, Object> meta) {
        String dir = str(meta.get("attachmentDirectory"));
        return dir != null && !dir.isEmpty() ? Path.of(dir) : null;
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
     *
     * <p>B7/C3: also folds in the {@code "since"} metadata bound (if present), using whichever of
     * {@code since} and the startDate/daysBack-derived instant is later — matching the shared
     * "later of the two" contract for combining an explicit since bound with another configured
     * start bound.
     *
     * @return a snowflake strictly after the resolved start time, or {@code null} if there is no
     *         meaningful lower bound — either neither bound could be parsed, or the later of the
     *         two falls at or before the Discord epoch, which would otherwise yield a negative
     *         (and therefore enormous, once read as unsigned) snowflake that filters out every
     *         message
     */
    static String computeAfterSnowflake(Map<String, Object> meta, int defaultDaysBack) {
        return snowflakeFrom(computeEffectiveSince(meta, defaultDaysBack));
    }

    /**
     * Resolves the effective lower time bound: whichever of {@code since} and the
     * startDate/daysBack-derived instant is later. Shared by {@link #computeAfterSnowflake}
     * (message pagination) and archived-thread discovery in {@link #load} (B4) so a server with
     * no explicit {@code since} bound doesn't page through a channel's entire archived-thread
     * history only to discard every one of those threads' messages against the default daysBack
     * bound.
     */
    static Instant computeEffectiveSince(Map<String, Object> meta, int defaultDaysBack) {
        String startDate = str(meta.get("startDate"));
        Instant startInstant;
        if (startDate != null && !startDate.isEmpty()) {
            startInstant = parseDate(startDate);
        } else {
            startInstant = Instant.now().minus(Duration.ofDays(defaultDaysBack));
        }

        Instant sinceInstant = parseDate(str(meta.get("since")));
        return laterOf(startInstant, sinceInstant);
    }

    private static String snowflakeFrom(Instant effectiveInstant) {
        if (effectiveInstant == null) return null;
        long epochMillis = effectiveInstant.toEpochMilli();
        if (epochMillis <= DiscordModels.DISCORD_EPOCH) return null;
        long snowflake = (epochMillis - DiscordModels.DISCORD_EPOCH) << 22;
        return Long.toUnsignedString(snowflake);
    }

    private static Instant laterOf(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private String computeBeforeSnowflake(Map<String, Object> meta) {
        String endDate = str(meta.get("endDate"));
        if (endDate == null || endDate.isEmpty()) return null;
        Instant endInstant = parseDate(endDate);
        if (endInstant == null) return null;
        long snowflake = (endInstant.toEpochMilli() - DiscordModels.DISCORD_EPOCH) << 22;
        return Long.toUnsignedString(snowflake);
    }

    private static Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(dateStr).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(dateStr);
            } catch (DateTimeParseException e2) {
                try {
                    // Try plain date (yyyy-MM-dd)
                    return LocalDate.parse(dateStr)
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
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

}
