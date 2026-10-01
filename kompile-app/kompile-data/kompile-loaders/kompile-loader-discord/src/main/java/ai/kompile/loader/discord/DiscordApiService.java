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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * HTTP client for Discord REST API v10.
 * Handles rate limiting, pagination, and authentication.
 */
@Slf4j
public class DiscordApiService {

    private static final String API_BASE = "https://discord.com/api/v10";
    private static final int MAX_MESSAGES_PER_REQUEST = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Bounded retry cap for rate limits, 5xx responses, and network errors (see {@link #get}). */
    private static final int MAX_RETRY_ATTEMPTS = 5;
    /** Ceiling on any single rate-limit wait, however long Discord asks us to wait. */
    private static final long MAX_RETRY_AFTER_MS = 60_000L;

    private final String botToken;
    private final String apiBase;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration rateLimitDelay;

    public DiscordApiService(String botToken) {
        this(botToken, Duration.ofMillis(500));
    }

    public DiscordApiService(String botToken, Duration rateLimitDelay) {
        this(botToken, rateLimitDelay, API_BASE);
    }

    /**
     * Test seam: lets tests point the client at a fake HTTP server instead of the real
     * Discord API.
     */
    DiscordApiService(String botToken, Duration rateLimitDelay, String apiBase) {
        this.botToken = botToken;
        this.rateLimitDelay = rateLimitDelay;
        this.apiBase = apiBase;
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
     * <p>Discord API v10's {@code before}, {@code after} and {@code around} query parameters
     * are mutually exclusive — only one is honored per request. To keep pagination
     * well-defined, this method pages backwards using {@code before} exclusively and never
     * sends {@code after} to Discord; {@code afterId} is instead enforced client-side as an
     * exclusive lower bound, comparing snowflake IDs numerically (never lexicographically).
     *
     * @param channelId  channel ID
     * @param limit      max messages to fetch (0 = unlimited)
     * @param afterId    exclusive lower bound: only messages with id strictly greater than
     *                   this snowflake are returned (null for no lower bound)
     * @param beforeId   exclusive upper bound: the first page is fetched with this snowflake
     *                   as the {@code before} cursor (null to start from the newest message
     *                   in the channel)
     * @return messages with {@code afterId < id < beforeId}, ordered newest-first, at most
     *         {@code limit} entries (all matching messages if {@code limit <= 0})
     */
    public List<Message> getChannelMessages(String channelId, int limit, String afterId, String beforeId)
            throws IOException, InterruptedException {
        List<Message> allMessages = new ArrayList<>();
        String currentBefore = beforeId;
        boolean unlimited = limit <= 0;
        Long afterSnowflake = afterId != null ? Long.parseUnsignedLong(afterId) : null;

        while (unlimited || allMessages.size() < limit) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Message fetch interrupted for channel {}", channelId);
                break;
            }

            int remaining = unlimited ? MAX_MESSAGES_PER_REQUEST : Math.min(limit - allMessages.size(), MAX_MESSAGES_PER_REQUEST);
            String requestBefore = currentBefore;
            Long beforeSnowflake = requestBefore != null ? Long.parseUnsignedLong(requestBefore) : null;
            StringBuilder url = new StringBuilder("/channels/" + channelId + "/messages?limit=" + remaining);
            // before/after/around are mutually exclusive in Discord API v10 (only the last one
            // wins) — page backwards with `before` only. afterId is enforced below as a
            // client-side lower bound instead of ever being sent as `after`.
            if (requestBefore != null) url.append("&before=").append(requestBefore);

            String json = get(url.toString());
            List<Message> page = objectMapper.readValue(json, new TypeReference<>() {});
            if (page.isEmpty()) break;

            // Messages come newest-first; the last one has the smallest (oldest) ID, which
            // becomes the `before` cursor for the next page. Compare numerically — never as
            // strings — since snowflakes are unsigned 64-bit values.
            String pageOldestId = page.get(page.size() - 1).id();
            long pageOldestSnowflake = Long.parseUnsignedLong(pageOldestId);
            boolean cursorAdvanced = beforeSnowflake == null
                    || Long.compareUnsigned(pageOldestSnowflake, beforeSnowflake) < 0;
            if (!cursorAdvanced) {
                // Defensive against a misbehaving server that ignores `before` (or otherwise
                // returns a page that isn't strictly older than the cursor): stop before adding
                // anything from this page, so a stuck cursor can never produce duplicates.
                break;
            }

            boolean lowerBoundReached = false;
            for (Message message : page) {
                long messageSnowflake = Long.parseUnsignedLong(message.id());
                if (afterSnowflake != null && Long.compareUnsigned(messageSnowflake, afterSnowflake) <= 0) {
                    // This message and every later one in the page (older, since pages are
                    // newest-first) are at or below the lower bound; stop before adding them.
                    lowerBoundReached = true;
                    break;
                }
                if (beforeSnowflake != null && Long.compareUnsigned(messageSnowflake, beforeSnowflake) >= 0) {
                    // Defensive: skip any message the server returned at or above the cursor we
                    // asked it to page before, rather than trusting it blindly.
                    continue;
                }
                allMessages.add(message);
                if (!unlimited && allMessages.size() >= limit) break;
            }

            boolean shortPage = page.size() < remaining;
            boolean limitReached = !unlimited && allMessages.size() >= limit;
            currentBefore = pageOldestId;

            // Stop on an exhausted lower bound, a short page (end of channel history), or the
            // limit being reached. (A cursor that fails to advance is handled above, before any
            // of this page's messages are added.)
            if (lowerBoundReached || shortPage || limitReached) break;
            rateLimitSleep();
        }

        return allMessages;
    }

    /**
     * Fetch messages from a channel with pagination, going forward from a checkpoint.
     *
     * <p>Incremental crawls only want messages newer than the last one already seen. Paging
     * backwards with {@link #getChannelMessages} instead would fetch the newest {@code limit}
     * messages, and if more than {@code limit} messages have arrived since the checkpoint, every
     * message between the checkpoint and that window would never be fetched. This method pages
     * forward using Discord API v10's {@code after} query parameter exclusively — never
     * {@code before} or {@code around}, which are mutually exclusive with it — so a backlog
     * larger than {@code limit} is drained one gap-free batch at a time across repeated calls.
     *
     * @param channelId  channel ID
     * @param limit      max messages to fetch (0 = unlimited); with a positive limit, returns the
     *                   OLDEST {@code limit} messages above {@code afterId}, so repeated calls
     *                   advance the checkpoint forward without skipping any
     * @param afterId    exclusive lower bound snowflake; only messages with id strictly greater
     *                   than this are returned (required — forward paging has no meaningful
     *                   starting point without a checkpoint)
     * @return messages with id {@code > afterId}, ordered newest-first — so {@code get(0)} is
     *         always the newest fetched message, matching {@link #getChannelMessages}'s contract
     *         — at most {@code limit} entries (all matching messages if {@code limit <= 0})
     */
    public List<Message> getChannelMessagesAfter(String channelId, int limit, String afterId)
            throws IOException, InterruptedException {
        List<Message> allMessages = new ArrayList<>();
        long cursor = Long.parseUnsignedLong(afterId);
        boolean unlimited = limit <= 0;

        while (unlimited || allMessages.size() < limit) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Message fetch interrupted for channel {}", channelId);
                break;
            }

            int remaining = unlimited ? MAX_MESSAGES_PER_REQUEST : Math.min(limit - allMessages.size(), MAX_MESSAGES_PER_REQUEST);
            long requestAfter = cursor;
            // before/after/around are mutually exclusive in Discord API v10 — page forward with
            // `after` only, never `before`, so a checkpoint further back than one page never
            // loses the messages in between.
            String url = "/channels/" + channelId + "/messages?limit=" + remaining
                    + "&after=" + Long.toUnsignedString(requestAfter);

            String json = get(url);
            List<Message> page = objectMapper.readValue(json, new TypeReference<>() {});
            if (page.isEmpty()) break;

            // Messages come back newest-first even under `after`, but don't rely on that: find
            // the numerically largest id in the page explicitly, since that — not page.get(0) —
            // is the correct next cursor if the server misorders the page.
            long pageMaxId = Long.parseUnsignedLong(page.get(0).id());
            for (Message message : page) {
                long id = Long.parseUnsignedLong(message.id());
                if (Long.compareUnsigned(id, pageMaxId) > 0) pageMaxId = id;
            }
            boolean cursorAdvanced = Long.compareUnsigned(pageMaxId, requestAfter) > 0;
            if (!cursorAdvanced) {
                // Defensive against a misbehaving server that ignores `after`: stop before adding
                // anything from this page, so a stuck cursor can never produce duplicates.
                break;
            }

            for (Message message : page) {
                long messageSnowflake = Long.parseUnsignedLong(message.id());
                if (Long.compareUnsigned(messageSnowflake, requestAfter) <= 0) {
                    // Defensive: skip any message the server returned at or below the cursor we
                    // asked it to page after, rather than trusting it blindly.
                    continue;
                }
                allMessages.add(message);
                if (!unlimited && allMessages.size() >= limit) break;
            }

            boolean shortPage = page.size() < remaining;
            boolean limitReached = !unlimited && allMessages.size() >= limit;
            cursor = pageMaxId;

            if (shortPage || limitReached) break;
            rateLimitSleep();
        }

        // Pages are fetched oldest-batch-first; sort the accumulated result newest-first so
        // get(0) is the true newest message, matching getChannelMessages's contract.
        allMessages.sort((a, b) -> Long.compareUnsigned(
                Long.parseUnsignedLong(b.id()), Long.parseUnsignedLong(a.id())));
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
     * Fetch archived public threads in a channel, paginating with {@code before}/{@code has_more}
     * until the channel is exhausted or {@code sinceBound} is reached.
     *
     * @param sinceBound exclusive lower bound on archive time, or {@code null} for no bound. An
     *                    archived thread has had no activity since it was archived, so once a
     *                    page yields a thread archived before this bound, every thread after it
     *                    (archived even earlier) is guaranteed irrelevant too and pagination stops.
     */
    public List<Channel> getArchivedPublicThreads(String channelId, Instant sinceBound)
            throws IOException, InterruptedException {
        return paginateArchivedThreads("/channels/" + channelId + "/threads/archived/public", sinceBound);
    }

    /**
     * Fetch archived private threads in a channel (requires MANAGE_THREADS), paginating the same
     * way as {@link #getArchivedPublicThreads}.
     */
    public List<Channel> getArchivedPrivateThreads(String channelId, Instant sinceBound)
            throws IOException, InterruptedException {
        try {
            return paginateArchivedThreads("/channels/" + channelId + "/threads/archived/private", sinceBound);
        } catch (DiscordForbiddenException e) {
            // 403 Forbidden if missing MANAGE_THREADS — not fatal, just means no private threads visible.
            log.debug("Cannot access private archived threads for channel {}: {}", channelId, e.getMessage());
            return List.of();
        }
    }

    private List<Channel> paginateArchivedThreads(String basePath, Instant sinceBound)
            throws IOException, InterruptedException {
        List<Channel> allThreads = new ArrayList<>();
        String before = null;

        while (true) {
            StringBuilder url = new StringBuilder(basePath);
            if (before != null) url.append("?before=").append(before);

            String json = get(url.toString());
            ThreadListResponse response = objectMapper.readValue(json, ThreadListResponse.class);
            List<Channel> threads = response.threads();
            if (threads == null || threads.isEmpty()) break;

            boolean pastSinceBound = false;
            for (Channel thread : threads) {
                if (sinceBound != null && isArchivedBefore(thread, sinceBound)) {
                    pastSinceBound = true;
                    break;
                }
                allThreads.add(thread);
            }
            if (pastSinceBound || !response.hasMore()) break;

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

    private static boolean isArchivedBefore(Channel thread, Instant bound) {
        if (thread.threadMetadata() == null || thread.threadMetadata().archiveTimestamp() == null) return false;
        try {
            return OffsetDateTime.parse(thread.threadMetadata().archiveTimestamp()).toInstant().isBefore(bound);
        } catch (DateTimeParseException e) {
            return false;
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

    /**
     * Issues one GET, retrying up to {@link #MAX_RETRY_ATTEMPTS} times for rate limits, 5xx
     * responses, and network-level {@link IOException}s. 403/404/other 4xx responses are not
     * retried — 403 is surfaced as a {@link DiscordForbiddenException} so callers can treat
     * missing access to a single resource as a skip rather than a fatal error.
     */
    private String get(String endpoint) throws IOException, InterruptedException {
        IOException lastFailure = null;
        int lastStatus = -1;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + endpoint))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "KompileBot (https://kompile.ai, 1.0)")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastFailure = e;
                lastStatus = -1;
                if (attempt == MAX_RETRY_ATTEMPTS) break;
                long waitMs = backoffMs(attempt);
                log.warn("Discord API request to {} failed ({}), retrying in {}ms (attempt {}/{})",
                        endpoint, e.getMessage(), waitMs, attempt, MAX_RETRY_ATTEMPTS);
                sleep(waitMs);
                continue;
            }

            int status = response.statusCode();

            if (status == 429) {
                lastStatus = status;
                lastFailure = new IOException("Discord API rate limited " + endpoint);
                if (attempt == MAX_RETRY_ATTEMPTS) break;
                long waitMs = retryAfterMillis(response, attempt);
                log.warn("Rate limited by Discord API for {}, waiting {}ms (attempt {}/{})",
                        endpoint, waitMs, attempt, MAX_RETRY_ATTEMPTS);
                sleep(waitMs);
                continue;
            }

            if (isRetryableServerError(status)) {
                lastStatus = status;
                lastFailure = new IOException("Discord API error " + status + " for " + endpoint + ": " + response.body());
                if (attempt == MAX_RETRY_ATTEMPTS) break;
                long waitMs = backoffMs(attempt);
                log.warn("Discord API returned {} for {}, retrying in {}ms (attempt {}/{})",
                        status, endpoint, waitMs, attempt, MAX_RETRY_ATTEMPTS);
                sleep(waitMs);
                continue;
            }

            if (status == 403) {
                throw new DiscordForbiddenException("Forbidden: bot lacks permission for " + endpoint);
            }
            if (status == 404) {
                throw new IOException("Not found: " + endpoint);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Discord API error " + status + " for " + endpoint + ": " + response.body());
            }

            return response.body();
        }

        String statusPart = lastStatus >= 0 ? ("last status " + lastStatus) : "network error";
        throw new IOException("Discord API request to " + endpoint + " failed after " + MAX_RETRY_ATTEMPTS
                + " attempts (" + statusPart + ")", lastFailure);
    }

    private static boolean isRetryableServerError(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    /** Exponential backoff for attempt 1, 2, 3, ...: 1s, 2s, 4s, 8s, ... */
    private static long backoffMs(int attempt) {
        return 1000L << (attempt - 1);
    }

    /**
     * Resolves how long to wait after a 429, preferring the {@code Retry-After} header (seconds),
     * then a {@code retry_after} field in the JSON body — both capped at
     * {@link #MAX_RETRY_AFTER_MS} — and finally the same bounded exponential backoff used for
     * 5xx/network errors if neither is present. Without this last fallback, a 429 with no usable
     * wait hint would wait the full 60s cap on every attempt, burning most of the retry budget
     * (up to 4 minutes across 5 attempts) on a single endpoint.
     */
    private long retryAfterMillis(HttpResponse<String> response, int attempt) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                double seconds = Double.parseDouble(header.get());
                return capRetryAfter(seconds);
            } catch (NumberFormatException ignored) {
                // fall through to body parsing
            }
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            if (node.has("retry_after")) {
                return capRetryAfter(node.get("retry_after").asDouble());
            }
        } catch (Exception ignored) {
            // no parseable JSON body / no retry_after field — fall back to backoff below
        }
        return backoffMs(attempt);
    }

    private static long capRetryAfter(double seconds) {
        long millis = (long) (seconds * 1000);
        return Math.min(MAX_RETRY_AFTER_MS, Math.max(0, millis));
    }

    private void rateLimitSleep() throws InterruptedException {
        if (rateLimitDelay != null && !rateLimitDelay.isZero()) {
            sleep(rateLimitDelay.toMillis());
        }
    }

    /**
     * Test seam: overridden by tests to observe/skip retry and rate-limit waits without actually
     * sleeping.
     */
    void sleep(long millis) throws InterruptedException {
        if (millis > 0) Thread.sleep(millis);
    }
}
