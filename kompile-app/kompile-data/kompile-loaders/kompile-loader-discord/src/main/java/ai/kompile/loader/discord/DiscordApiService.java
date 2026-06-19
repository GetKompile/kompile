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

import ai.kompile.loader.discord.DiscordModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for Discord REST API v10.
 * Handles rate limiting, pagination, and authentication.
 */
@Slf4j
public class DiscordApiService {

    private static final String API_BASE = "https://discord.com/api/v10";
    private static final int MAX_MESSAGES_PER_REQUEST = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration rateLimitDelay;

    public DiscordApiService(String botToken) {
        this(botToken, Duration.ofMillis(500));
    }

    public DiscordApiService(String botToken, Duration rateLimitDelay) {
        this.botToken = botToken;
        this.rateLimitDelay = rateLimitDelay;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Fetch guild (server) info.
     */
    public Guild getGuild(String guildId) throws IOException, InterruptedException {
        String json = get("/guilds/" + guildId + "?with_counts=true");
        return objectMapper.readValue(json, Guild.class);
    }

    /**
     * Fetch all channels for a guild.
     */
    public List<Channel> getGuildChannels(String guildId) throws IOException, InterruptedException {
        String json = get("/guilds/" + guildId + "/channels");
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * Fetch roles for a guild.
     */
    public List<Role> getGuildRoles(String guildId) throws IOException, InterruptedException {
        String json = get("/guilds/" + guildId + "/roles");
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * Fetch guild members with pagination.
     */
    public List<Member> getGuildMembers(String guildId, int limit) throws IOException, InterruptedException {
        List<Member> allMembers = new ArrayList<>();
        String afterId = "0";
        int perPage = Math.min(limit, 1000);

        while (allMembers.size() < limit) {
            int remaining = limit - allMembers.size();
            int fetchCount = Math.min(remaining, perPage);
            String json = get("/guilds/" + guildId + "/members?limit=" + fetchCount + "&after=" + afterId);
            List<Member> page = objectMapper.readValue(json, new TypeReference<>() {});
            if (page.isEmpty()) break;
            allMembers.addAll(page);
            afterId = page.get(page.size() - 1).user().id();
            if (page.size() < fetchCount) break;
            rateLimitSleep();
        }

        return allMembers;
    }

    /**
     * Fetch messages from a channel with pagination, going backwards from newest.
     *
     * @param channelId  channel ID
     * @param limit      max messages to fetch (0 = unlimited)
     * @param afterId    only fetch messages after this snowflake ID (null for latest)
     * @param beforeId   only fetch messages before this snowflake ID (null for oldest)
     * @return list of messages ordered newest-first
     */
    public List<Message> getChannelMessages(String channelId, int limit, String afterId, String beforeId)
            throws IOException, InterruptedException {
        List<Message> allMessages = new ArrayList<>();
        String currentBefore = beforeId;
        boolean unlimited = limit <= 0;

        while (unlimited || allMessages.size() < limit) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Message fetch interrupted for channel {}", channelId);
                break;
            }

            int remaining = unlimited ? MAX_MESSAGES_PER_REQUEST : Math.min(limit - allMessages.size(), MAX_MESSAGES_PER_REQUEST);
            StringBuilder url = new StringBuilder("/channels/" + channelId + "/messages?limit=" + remaining);
            if (currentBefore != null) url.append("&before=").append(currentBefore);
            if (afterId != null) url.append("&after=").append(afterId);

            String json = get(url.toString());
            List<Message> page = objectMapper.readValue(json, new TypeReference<>() {});
            if (page.isEmpty()) break;

            allMessages.addAll(page);
            // Messages come newest-first; the last one has the oldest ID
            currentBefore = page.get(page.size() - 1).id();

            if (page.size() < remaining) break;
            rateLimitSleep();
        }

        return allMessages;
    }

    /**
     * Fetch active threads in a guild.
     */
    public List<Channel> getActiveThreads(String guildId) throws IOException, InterruptedException {
        String json = get("/guilds/" + guildId + "/threads/active");
        ThreadListResponse response = objectMapper.readValue(json, ThreadListResponse.class);
        return response.threads() != null ? response.threads() : List.of();
    }

    /**
     * Fetch archived public threads in a channel.
     */
    public List<Channel> getArchivedPublicThreads(String channelId) throws IOException, InterruptedException {
        List<Channel> allThreads = new ArrayList<>();
        String before = null;

        while (true) {
            StringBuilder url = new StringBuilder("/channels/" + channelId + "/threads/archived/public");
            if (before != null) url.append("?before=").append(before);

            String json = get(url.toString());
            ThreadListResponse response = objectMapper.readValue(json, ThreadListResponse.class);
            List<Channel> threads = response.threads();
            if (threads == null || threads.isEmpty()) break;

            allThreads.addAll(threads);
            if (!response.hasMore()) break;

            // Use the last thread's archive timestamp for pagination
            Channel lastThread = threads.get(threads.size() - 1);
            if (lastThread.threadMetadata() != null) {
                before = lastThread.threadMetadata().archiveTimestamp();
            } else {
                break;
            }
            rateLimitSleep();
        }

        return allThreads;
    }

    /**
     * Fetch archived private threads in a channel (requires MANAGE_THREADS).
     */
    public List<Channel> getArchivedPrivateThreads(String channelId) throws IOException, InterruptedException {
        try {
            String json = get("/channels/" + channelId + "/threads/archived/private");
            ThreadListResponse response = objectMapper.readValue(json, ThreadListResponse.class);
            return response.threads() != null ? response.threads() : List.of();
        } catch (IOException e) {
            // 403 Forbidden if missing MANAGE_THREADS — not fatal
            log.debug("Cannot access private archived threads for channel {}: {}", channelId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Download an attachment file to a temp directory.
     *
     * @return path to the downloaded temp file
     */
    public Path downloadAttachment(Attachment attachment) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(attachment.url()))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download attachment " + attachment.filename()
                    + ": HTTP " + response.statusCode());
        }

        String suffix = "";
        String filename = attachment.filename();
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0) suffix = filename.substring(dotIdx);

        Path tempFile = Files.createTempFile("kompile-discord-attachment-", suffix);
        tempFile.toFile().deleteOnExit();
        try (InputStream is = response.body()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }

    private String get(String endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + endpoint))
                .header("Authorization", "Bot " + botToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "KompileBot (https://kompile.ai, 1.0)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Handle rate limiting
        if (response.statusCode() == 429) {
            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse("1");
            long retryAfterMs = (long) (Double.parseDouble(retryAfterHeader) * 1000);
            log.warn("Rate limited by Discord API, waiting {}ms", retryAfterMs);
            Thread.sleep(retryAfterMs + 100);
            return get(endpoint); // retry
        }

        if (response.statusCode() == 403) {
            throw new IOException("Forbidden: bot lacks permission for " + endpoint);
        }
        if (response.statusCode() == 404) {
            throw new IOException("Not found: " + endpoint);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Discord API error " + response.statusCode()
                    + " for " + endpoint + ": " + response.body());
        }

        return response.body();
    }

    private void rateLimitSleep() throws InterruptedException {
        if (rateLimitDelay != null && !rateLimitDelay.isZero()) {
            Thread.sleep(rateLimitDelay.toMillis());
        }
    }
}
