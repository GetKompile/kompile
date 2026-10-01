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
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DiscordLoaderImpl#computeAfterSnowflake} treats the Discord epoch as a floor.
 * A start time at or before {@link DiscordModels#DISCORD_EPOCH} must yield {@code null} (no lower
 * bound) instead of the value {@code (epochMillis - DISCORD_EPOCH) << 22} would otherwise produce
 * from a negative difference — a huge number once read as unsigned — which would silently filter
 * out every message once enforced as a client-side lower bound in {@link DiscordApiService}.
 */
class DiscordLoaderImplTest {

    @Test
    void preEpochStartDateReturnsNull() {
        String result = DiscordLoaderImpl.computeAfterSnowflake(Map.of("startDate", "2014-06-01"), 30);

        assertNull(result, "a start date before the Discord epoch must yield no lower bound");
    }

    @Test
    void daysBackBeforeEpochReturnsNull() {
        String result = DiscordLoaderImpl.computeAfterSnowflake(Map.of(), 5000);

        assertNull(result, "5000 days back is before the Discord epoch and must yield no lower bound");
    }

    @Test
    void defaultDaysBackReturnsSnowflakeBelowNow() {
        String result = DiscordLoaderImpl.computeAfterSnowflake(Map.of(), 30);

        assertNotNull(result, "30 days back is well after the Discord epoch");
        long resultSnowflake = Long.parseUnsignedLong(result);
        long nowSnowflake = (Instant.now().toEpochMilli() - DiscordModels.DISCORD_EPOCH) << 22;
        assertTrue(Long.compareUnsigned(resultSnowflake, nowSnowflake) < 0,
                "a snowflake 30 days back must be unsigned-less-than a snowflake for now");
    }

    // === End-to-end tests against a fake Discord HTTP server, exercising load()'s wiring of
    // === B2 (per-channel/thread isolation), B3 (active-thread parent filtering), B4 (archived
    // === thread pagination), B6 (total message cap), B7 (since bound) and B8 (source_path).

    @Test
    void perChannelFailureIsIsolatedOtherChannelsStillLoad() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200,
                    channelListJson(channelJson("c1", 0, null, "chan-ok"), channelJson("c2", 0, null, "chan-bad")));
            fake.respond("/channels/c1/messages", 200,
                    messageListJson(messageJson(recentId(1), "hi", null), messageJson(recentId(2), "there", null)));
            fake.respond("/channels/c2/messages", 503, "Service Unavailable");

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl)
                    .load(descriptorFor(baseMeta("g1", false)));

            assertEquals(2, docs.size(), "the healthy channel's messages must still load");
            for (Document doc : docs) {
                assertEquals("c1", doc.getMetadata().get("discord.channelId"));
            }
        }
    }

    @Test
    void allChannelsFailingThrowsWithFirstFailureAsCause() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200,
                    channelListJson(channelJson("c1", 0, null, "chan-a"), channelJson("c2", 0, null, "chan-b")));
            fake.respond("/channels/c1/messages", 503, "Service Unavailable");
            fake.respond("/channels/c2/messages", 503, "Service Unavailable");

            FakeApiDiscordLoader loader = new FakeApiDiscordLoader(fake.baseUrl);
            DocumentSourceDescriptor descriptor = descriptorFor(baseMeta("g1", false));

            Exception ex = assertThrows(Exception.class, () -> loader.load(descriptor));

            assertTrue(ex.getMessage().contains("All 2 targeted Discord channel(s) failed"), ex.getMessage());
            assertNotNull(ex.getCause());
        }
    }

    @Test
    void forbiddenChannelIsSkippedNotCountedAsFailure() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200, channelListJson(channelJson("c1", 0, null, "chan-403")));
            fake.respond("/channels/c1/messages", 403, "{\"message\":\"Missing Access\",\"code\":50001}");

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl)
                    .load(descriptorFor(baseMeta("g1", false)));

            assertTrue(docs.isEmpty(), "a 403-skipped channel must not throw or fail the whole load");
        }
    }

    @Test
    void threadDiscoveryForbiddenOnOneChannelDoesNotAbortLoad() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200,
                    channelListJson(channelJson("c1", 0, null, "chan-403-threads"), channelJson("c2", 0, null, "chan-ok")));
            fake.respond("/guilds/g1/threads/active", 200, emptyThreadList());
            // c1 is readable for messages but 403s on archived-public-thread discovery specifically
            // (Discord returns 403 on /threads/archived/public for a channel missing
            // READ_MESSAGE_HISTORY/VIEW_CHANNEL) — this must only drop c1's threads, not abort the
            // whole load before either channel's messages are ever fetched.
            fake.respond("/channels/c1/messages", 200,
                    messageListJson(messageJson(recentId(1), "c1 message", null)));
            fake.respond("/channels/c1/threads/archived/public", 403, "{\"message\":\"Missing Access\",\"code\":50001}");
            fake.respond("/channels/c1/threads/archived/private", 200, emptyThreadList());
            fake.respond("/channels/c2/messages", 200,
                    messageListJson(messageJson(recentId(2), "c2 message", null)));
            fake.respond("/channels/c2/threads/archived/public", 200, emptyThreadList());
            fake.respond("/channels/c2/threads/archived/private", 200, emptyThreadList());

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(baseMeta("g1", true)));

            assertEquals(2, docs.size(),
                    "both channels' messages must load even though c1's archived-public-thread discovery 403s");
        }
    }

    @Test
    void archivedThreadOlderThanDaysBackIsNotPaginatedOrFetched() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200, channelListJson(channelJson("c1", 0, null, "chan-1")));
            fake.respond("/guilds/g1/threads/active", 200, emptyThreadList());
            fake.respond("/channels/c1/messages", 200, "[]");
            fake.respond("/channels/c1/threads/archived/private", 200, emptyThreadList());

            // No "since" is configured and daysBack defaults to 30 — the loader must still apply
            // the resulting ~30-day-back bound to archived-thread discovery (the same effective
            // bound computeAfterSnowflake resolves for message pagination), not page unboundedly
            // through the channel's entire archived-thread history. recentThread is within the
            // bound; olderThread is outside it and must stop pagination there, before a third,
            // even-older page is ever requested.
            String recentArchiveTs = Instant.now().minus(Duration.ofDays(5)).toString();
            String olderArchiveTs = Instant.now().minus(Duration.ofDays(40)).toString();
            String ancientArchiveTs = Instant.now().minus(Duration.ofDays(400)).toString();

            AtomicInteger pageCalls = new AtomicInteger(0);
            fake.server.createContext("/channels/c1/threads/archived/public", exchange -> {
                int call = pageCalls.incrementAndGet();
                String body = switch (call) {
                    case 1 -> threadListJson("true", archivedThreadChannelJson("recent-thread", "c1", "recent", recentArchiveTs));
                    case 2 -> threadListJson("true", archivedThreadChannelJson("older-thread", "c1", "older", olderArchiveTs));
                    default -> threadListJson("false", archivedThreadChannelJson("ancient-thread", "c1", "ancient", ancientArchiveTs));
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            fake.respond("/channels/recent-thread/messages", 200,
                    messageListJson(messageJson(recentId(1), "recent thread message", null)));
            AtomicInteger olderThreadCalls = fake.countingRespond("/channels/older-thread/messages", 200, "[]");
            AtomicInteger ancientThreadCalls = fake.countingRespond("/channels/ancient-thread/messages", 200, "[]");

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(baseMeta("g1", true)));

            assertEquals(2, pageCalls.get(),
                    "must stop once older-thread (40 days back) crosses the implicit 30-day daysBack "
                            + "bound, never requesting the third page");
            assertEquals(0, olderThreadCalls.get(), "a thread archived before the daysBack bound must never be fetched");
            assertEquals(0, ancientThreadCalls.get(), "pagination must never reach the third page at all");
            assertEquals(1, docs.size(), "only the recent thread's message must load");
        }
    }

    @Test
    void activeAndArchivedThreadsAreFilteredToTargetedChannelParents() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200,
                    channelListJson(channelJson("ca", 0, null, "chan-a"), channelJson("cb", 0, null, "chan-b")));
            fake.respond("/channels/ca/messages", 200,
                    messageListJson(messageJson(recentId(1), "root message", null)));
            AtomicInteger t2Calls = fake.countingRespond("/channels/t2/messages", 200, "[]");

            // t1 is parented under the targeted channel (ca) and must be included; t2 is parented
            // under a channel that exists in the guild but isn't targeted (cb) and must be excluded.
            fake.respond("/guilds/g1/threads/active", 200,
                    threadListJson("false", channelJson("t1", 11, "ca", "thread-1"), channelJson("t2", 11, "cb", "thread-2")));
            fake.respond("/channels/t1/messages", 200,
                    messageListJson(messageJson(recentId(2), "thread-1 message", null)));
            fake.respond("/channels/ca/threads/archived/public", 200,
                    threadListJson("false", channelJson("t3", 11, "ca", "thread-3")));
            fake.respond("/channels/ca/threads/archived/private", 200, emptyThreadList());
            fake.respond("/channels/t3/messages", 200,
                    messageListJson(messageJson(recentId(3), "thread-3 message", null)));

            Map<String, Object> meta = baseMeta("g1", true);
            meta.put("channelIds", "ca");

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(meta));

            assertEquals(3, docs.size(), "chan-a's own message plus thread-1 and thread-3's messages");
            assertEquals(0, t2Calls.get(), "thread-2 (parented under the non-targeted chan-b) must never be fetched");
        }
    }

    @Test
    void totalMaxMessagesCapAppliesAcrossChannelsNotPerChannel() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200,
                    channelListJson(channelJson("c1", 0, null, "chan-1"), channelJson("c2", 0, null, "chan-2")));
            String[] chan1Messages = new String[5];
            for (int i = 0; i < 5; i++) chan1Messages[i] = messageJson(recentId(100 - i), "m" + i, null);
            fake.respond("/channels/c1/messages", 200, messageListJson(chan1Messages));
            AtomicInteger chan2Calls = fake.countingRespond("/channels/c2/messages", 200, "[]");

            Map<String, Object> meta = baseMeta("g1", false);
            meta.put("maxMessages", 3);

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(meta));

            assertEquals(3, docs.size(), "maxMessages is a TOTAL cap, not per-channel");
            assertEquals(0, chan2Calls.get(), "the budget must be exhausted by chan-1 before chan-2 is ever queried");
        }
    }

    @Test
    void sinceBoundFiltersOutMessagesBeforeIt() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200, channelListJson(channelJson("c1", 0, null, "chan-1")));

            Instant since = Instant.now().minusSeconds(3600);
            long sinceSnowflake = (since.toEpochMilli() - DiscordModels.DISCORD_EPOCH) << 22;
            String newId = Long.toUnsignedString(sinceSnowflake + 1_000_000_000L);
            String oldId = Long.toUnsignedString(sinceSnowflake - 1_000_000_000L);
            fake.respond("/channels/c1/messages", 200,
                    messageListJson(messageJson(newId, "new enough", null), messageJson(oldId, "too old", null)));

            Map<String, Object> meta = baseMeta("g1", false);
            meta.put("since", since.toString());

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(meta));

            assertEquals(1, docs.size());
            assertEquals(newId, docs.get(0).getMetadata().get("discord.messageId"));
        }
    }

    @Test
    void sourcePathUsesDoubleSlashSchemeWithGuildChannelAndMessageIds() throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200, channelListJson(channelJson("c1", 0, null, "chan-1")));
            String messageId = recentId(1);
            fake.respond("/channels/c1/messages", 200, messageListJson(messageJson(messageId, "hello", null)));

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl)
                    .load(descriptorFor(baseMeta("g1", false)));

            assertEquals(1, docs.size());
            String expected = "discord://g1/c1/" + messageId;
            assertEquals(expected, docs.get(0).getMetadata().get(GraphConstants.META_SOURCE_PATH));
            assertEquals(expected, docs.get(0).getMetadata().get(GraphConstants.META_SOURCE));
        }
    }

    @Test
    void attachmentDirectoryWiringSavesFileAndAnnotatesDocument(@TempDir Path attachmentDirectory) throws Exception {
        try (FakeDiscordServer fake = new FakeDiscordServer()) {
            fake.respond("/guilds/g1", 200, guildJson("g1"));
            fake.respond("/guilds/g1/roles", 200, "[]");
            fake.respond("/guilds/g1/channels", 200, channelListJson(channelJson("c1", 0, null, "chan-1")));
            String messageId = recentId(1);
            byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
            fake.bytes("/cdn/notes.txt", content, "text/plain");
            String attJson = "{\"id\":\"att1\",\"filename\":\"notes.txt\",\"content_type\":\"text/plain\","
                    + "\"size\":" + content.length + ",\"url\":\"" + fake.baseUrl + "/cdn/notes.txt\","
                    + "\"proxy_url\":\"" + fake.baseUrl + "/cdn/notes.txt\",\"width\":null,\"height\":null}";
            fake.respond("/channels/c1/messages", 200,
                    messageListJson(messageJson(messageId, "see attached", "[" + attJson + "]")));

            Map<String, Object> meta = baseMeta("g1", false);
            meta.put("attachmentDirectory", attachmentDirectory.toString());

            List<Document> docs = new FakeApiDiscordLoader(fake.baseUrl).load(descriptorFor(meta));

            assertEquals(1, docs.size());
            String sourcePath = (String) docs.get(0).getMetadata().get(GraphConstants.META_SOURCE_PATH);
            String messageKey = DiscordAttachmentStorage.messageKey(sourcePath);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments =
                    (List<Map<String, Object>>) docs.get(0).getMetadata().get("attachments");
            assertEquals(1, attachments.size());
            assertEquals(messageKey + "/notes.txt", attachments.get(0).get("path"));
            Path saved = attachmentDirectory.resolve(messageKey).resolve("notes.txt");
            assertTrue(Files.exists(saved));
            assertArrayEquals(content, Files.readAllBytes(saved));
        }
    }

    // === Fixtures shared by the tests above ===

    private static Map<String, Object> baseMeta(String guildId, boolean includeThreads) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("botToken", "tok");
        meta.put("guildId", guildId);
        meta.put("includeThreads", includeThreads);
        return meta;
    }

    private static DocumentSourceDescriptor descriptorFor(Map<String, Object> meta) {
        return DocumentSourceDescriptor.builder()
                .type(SourceType.DISCORD)
                .metadata(meta)
                .build();
    }

    /**
     * A snowflake comfortably after the default 30-day-back lower bound {@link DiscordLoaderImpl}
     * applies when no {@code since}/{@code startDate} is configured, regardless of what "now" is
     * when the test runs.
     */
    private static String recentId(long offset) {
        String bound = DiscordLoaderImpl.computeAfterSnowflake(Map.of(), 30);
        long boundValue = bound != null ? Long.parseUnsignedLong(bound) : 0L;
        return Long.toUnsignedString(boundValue + 1_000_000_000L + offset);
    }

    private static String guildJson(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Test Guild\",\"icon\":null,\"owner_id\":\"owner1\","
                + "\"description\":null,\"member_count\":10,\"preferred_locale\":\"en-US\"}";
    }

    private static String channelJson(String id, int type, String parentId, String name) {
        return "{\"id\":\"" + id + "\",\"type\":" + type + ",\"guild_id\":\"g1\",\"name\":\"" + name + "\","
                + "\"topic\":null,\"position\":0,"
                + "\"parent_id\":" + (parentId != null ? "\"" + parentId + "\"" : "null") + ","
                + "\"last_message_id\":null,\"message_count\":null,\"thread_metadata\":null}";
    }

    private static String channelListJson(String... channelJsons) {
        return "[" + String.join(",", channelJsons) + "]";
    }

    private static String threadListJson(String hasMore, String... threadJsons) {
        return "{\"threads\":[" + String.join(",", threadJsons) + "],\"members\":[],\"has_more\":" + hasMore + "}";
    }

    private static String emptyThreadList() {
        return "{\"threads\":[],\"members\":[],\"has_more\":false}";
    }

    /** A thread channel JSON with archive/thread_metadata populated, for archived-thread pagination tests. */
    private static String archivedThreadChannelJson(String id, String parentId, String name, String archiveTimestamp) {
        return "{\"id\":\"" + id + "\",\"type\":11,\"guild_id\":\"g1\",\"name\":\"" + name + "\","
                + "\"topic\":null,\"position\":0,\"parent_id\":\"" + parentId + "\",\"last_message_id\":null,"
                + "\"message_count\":0,\"thread_metadata\":{\"archived\":true,\"auto_archive_duration\":1440,"
                + "\"archive_timestamp\":\"" + archiveTimestamp + "\",\"locked\":false}}";
    }

    private static String messageJson(String id, String content, String attachmentsJson) {
        return "{\"id\":\"" + id + "\","
                + "\"channel_id\":\"c\","
                + "\"author\":{\"id\":\"u1\",\"username\":\"tester\",\"discriminator\":\"0\","
                + "\"global_name\":null,\"avatar\":null,\"bot\":false},"
                + "\"content\":\"" + content + "\","
                + "\"timestamp\":\"2024-01-01T00:00:00.000000+00:00\","
                + "\"edited_timestamp\":null,"
                + "\"type\":0,"
                + "\"message_reference\":null,"
                + "\"attachments\":" + (attachmentsJson != null ? attachmentsJson : "[]") + ","
                + "\"embeds\":[],\"reactions\":[],\"mentions\":[],\"mention_roles\":[],"
                + "\"mention_everyone\":false,\"pinned\":false,\"thread\":null}";
    }

    private static String messageListJson(String... messageJsons) {
        return "[" + String.join(",", messageJsons) + "]";
    }

    /** Test seam: points the loader's internal {@link DiscordApiService} at a fake HTTP server. */
    private static final class FakeApiDiscordLoader extends DiscordLoaderImpl {
        private final String apiBase;

        FakeApiDiscordLoader(String apiBase) {
            this.apiBase = apiBase;
        }

        @Override
        DiscordApiService createApiService(String botToken, Duration rateLimitDelay) {
            return new NoSleepDiscordApiService(botToken, apiBase);
        }
    }

    /** Avoids real retry/rate-limit sleeps so channel-failure tests run instantly. */
    private static class NoSleepDiscordApiService extends DiscordApiService {
        NoSleepDiscordApiService(String botToken, String apiBase) {
            super(botToken, Duration.ZERO, apiBase);
        }

        @Override
        void sleep(long millis) {
            // no-op
        }
    }

    /** Minimal in-process stand-in for the Discord REST API, backed by the JDK's HttpServer. */
    private static final class FakeDiscordServer implements AutoCloseable {
        final HttpServer server;
        final String baseUrl;

        FakeDiscordServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void respond(String path, int status, String body) {
            server.createContext(path, exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
        }

        AtomicInteger countingRespond(String path, int status, String body) {
            AtomicInteger calls = new AtomicInteger(0);
            server.createContext(path, exchange -> {
                calls.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            return calls;
        }

        void bytes(String path, byte[] content, String contentType) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(content);
                }
            });
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
