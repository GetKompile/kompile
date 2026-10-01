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

import ai.kompile.loader.discord.DiscordModels.Channel;
import ai.kompile.loader.discord.DiscordModels.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DiscordApiService#getChannelMessages} pages backwards using Discord's
 * {@code before} cursor exclusively, and {@link DiscordApiService#getChannelMessagesAfter} pages
 * forward using {@code after} exclusively. Discord API v10's {@code before}, {@code after} and
 * {@code around} query parameters are mutually exclusive — only the last one sent is honored —
 * so {@code getChannelMessages}'s {@code afterId} must be enforced client-side as a lower bound
 * instead of ever being sent as {@code after}, comparing snowflake IDs numerically rather than
 * lexicographically.
 *
 * <p>Backed by the JDK's {@link HttpServer} standing in for the Discord REST API, serving a
 * synthetic channel of 250 messages with increasing snowflake IDs using real Discord pagination
 * semantics: newest-first pages of the {@code limit} messages immediately older than a
 * {@code before} cursor (or the newest messages with no cursor), and newest-first pages of the
 * {@code limit} messages immediately above an {@code after} cursor.
 */
class DiscordApiServiceTest {

    private static final String CHANNEL_ID = "channel-1";
    /** Channel whose fake server ignores `before` and always returns the newest page. */
    private static final String MISBEHAVING_CHANNEL_ID = "channel-misbehaving";
    private static final int CHANNEL_SIZE = 250;
    /** Snowflake-magnitude base so IDs look realistic; messages are BASE_ID..BASE_ID+249. */
    private static final long BASE_ID = 100_000_000_000_000_000L;
    private static final long MAX_ID = BASE_ID + CHANNEL_SIZE - 1;

    /** Ascending (oldest-first) snowflake IDs for the synthetic channel. */
    private static final List<Long> CHANNEL_IDS =
            LongStream.range(0, CHANNEL_SIZE).mapToObj(i -> BASE_ID + i).toList();

    private HttpServer server;
    private List<String> recordedQueries;
    private DiscordApiService api;

    @BeforeEach
    void setUp() throws IOException {
        recordedQueries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/channels/" + CHANNEL_ID + "/messages", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            recordedQueries.add(query == null ? "" : query);
            byte[] body = pageResponse(query).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/channels/" + MISBEHAVING_CHANNEL_ID + "/messages", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            recordedQueries.add(query == null ? "" : query);
            byte[] body = misbehavingPageResponse(query).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new DiscordApiService("fake-token", Duration.ZERO, baseUrl);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void neverSendsAfterAndReturnsExactlyMessagesNewerThanAfterIdAcrossPages() throws Exception {
        String afterId = Long.toUnsignedString(BASE_ID + 50);

        List<Message> result = api.getChannelMessages(CHANNEL_ID, 0, afterId, null);

        assertEquals(2, recordedQueries.size(),
                "expected the newest full page, then a page truncated at afterId");
        for (String query : recordedQueries) {
            assertFalse(query.contains("after="), "must never send `after`: " + query);
            assertFalse(query.contains("before=") && query.contains("after="),
                    "must never send both `before` and `after`: " + query);
        }
        assertFalse(recordedQueries.get(0).contains("before="), "first request must start uncursored");
        assertTrue(recordedQueries.get(1).contains("before="), "later pages must cursor with `before`");

        List<Long> expectedIds = new ArrayList<>();
        for (long id = MAX_ID; id > BASE_ID + 50; id--) expectedIds.add(id);
        List<Long> actualIds = idsOf(result);
        assertEquals(expectedIds, actualIds, "must be exactly the messages with id > afterId, newest-first");
        assertEquals(expectedIds.size(), new HashSet<>(actualIds).size(), "no duplicates");
    }

    @Test
    void limitReturnsNewestMessagesWithExpectedPageSizes() throws Exception {
        List<Message> result = api.getChannelMessages(CHANNEL_ID, 150, null, null);

        List<Long> expectedIds = new ArrayList<>();
        for (long id = MAX_ID; id > MAX_ID - 150; id--) expectedIds.add(id);
        assertEquals(expectedIds, idsOf(result));

        assertEquals(2, recordedQueries.size());
        assertTrue(recordedQueries.get(0).contains("limit=100"), recordedQueries.get(0));
        assertTrue(recordedQueries.get(1).contains("limit=50"), recordedQueries.get(1));
    }

    @Test
    void unlimitedModeReturnsEntireChannelAndTerminates() throws Exception {
        List<Message> result = api.getChannelMessages(CHANNEL_ID, 0, null, null);

        List<Long> actualIds = idsOf(result);
        assertEquals(CHANNEL_SIZE, actualIds.size());
        assertEquals(CHANNEL_SIZE, new HashSet<>(actualIds).size(), "no duplicates");
        for (int i = 0; i < actualIds.size() - 1; i++) {
            assertTrue(actualIds.get(i) > actualIds.get(i + 1), "must be strictly newest-first");
        }
        assertEquals(MAX_ID, actualIds.get(0));
        assertEquals(BASE_ID, actualIds.get(actualIds.size() - 1));
        assertEquals(3, recordedQueries.size(), "100 + 100 + 50 = 250 across three pages");
    }

    @Test
    void beforeIdIsExclusiveUpperBound() throws Exception {
        long cursorId = BASE_ID + 200;

        List<Message> result = api.getChannelMessages(
                CHANNEL_ID, 5, null, Long.toUnsignedString(cursorId));

        List<Long> expectedIds = List.of(
                cursorId - 1, cursorId - 2, cursorId - 3, cursorId - 4, cursorId - 5);
        assertEquals(expectedIds, idsOf(result));
        assertFalse(idsOf(result).contains(cursorId), "the cursor id itself must be excluded");
    }

    @Test
    void afterIdNewerThanEveryMessageYieldsEmptyResultAfterOneRequest() throws Exception {
        String afterId = Long.toUnsignedString(MAX_ID + 100);

        List<Message> result = api.getChannelMessages(CHANNEL_ID, 0, afterId, null);

        assertTrue(result.isEmpty());
        assertEquals(1, recordedQueries.size());
        assertFalse(recordedQueries.get(0).contains("after="));
    }

    @Test
    void misbehavingServerThatIgnoresBeforeTerminatesWithoutDuplicates() throws Exception {
        List<Message> result = api.getChannelMessages(MISBEHAVING_CHANNEL_ID, 0, null, null);

        List<Long> expectedIds = new ArrayList<>();
        for (long id = MAX_ID; id > MAX_ID - 100; id--) expectedIds.add(id);
        assertEquals(expectedIds, idsOf(result), "must be exactly the first (newest) page");
        assertEquals(expectedIds.size(), new HashSet<>(idsOf(result)).size(), "no duplicates");
        assertEquals(2, recordedQueries.size(),
                "one request for the first page, one more that detects the stuck cursor and stops");
    }

    @Test
    void getChannelMessagesAfterPagesForwardAcrossPagesWithoutGaps() throws Exception {
        String afterId = Long.toUnsignedString(BASE_ID + 50);

        List<Message> result = api.getChannelMessagesAfter(CHANNEL_ID, 0, afterId);

        List<Long> expectedIds = new ArrayList<>();
        for (long id = MAX_ID; id > BASE_ID + 50; id--) expectedIds.add(id);
        List<Long> actualIds = idsOf(result);
        assertEquals(expectedIds, actualIds, "must be exactly the messages with id > afterId, newest-first");
        assertEquals(expectedIds.size(), new HashSet<>(actualIds).size(), "no duplicates");

        assertFalse(recordedQueries.isEmpty());
        for (String query : recordedQueries) {
            assertTrue(query.contains("after="), "must always send `after`: " + query);
            assertFalse(query.contains("before="), "must never send `before`: " + query);
        }
    }

    @Test
    void getChannelMessagesAfterWithLimitReturnsOldestMessagesAboveCursor() throws Exception {
        String afterId = Long.toUnsignedString(BASE_ID + 50);

        List<Message> result = api.getChannelMessagesAfter(CHANNEL_ID, 5, afterId);

        List<Long> expectedIds = List.of(
                BASE_ID + 55, BASE_ID + 54, BASE_ID + 53, BASE_ID + 52, BASE_ID + 51);
        assertEquals(expectedIds, idsOf(result), "must be the OLDEST 5 messages above afterId");
        assertEquals(BASE_ID + 55, idsOf(result).get(0), "get(0) must be the max id of the fetched batch");
    }

    @Test
    void getChannelMessagesAfterMisbehavingServerThatIgnoresAfterTerminatesWithoutDuplicates() throws Exception {
        String afterId = Long.toUnsignedString(BASE_ID + 50);

        List<Message> result = api.getChannelMessagesAfter(MISBEHAVING_CHANNEL_ID, 0, afterId);

        List<Long> expectedIds = new ArrayList<>();
        for (long id = MAX_ID; id > MAX_ID - 100; id--) expectedIds.add(id);
        assertEquals(expectedIds, idsOf(result), "must be exactly the (misbehaving) server's one real page");
        assertEquals(expectedIds.size(), new HashSet<>(idsOf(result)).size(), "no duplicates");
        assertEquals(2, recordedQueries.size(),
                "one request for the first page, one more that detects the stuck cursor and stops");
    }

    @Test
    void getChannelMessagesAfterWithAfterIdAboveEveryMessageYieldsEmptyResultAfterOneRequest() throws Exception {
        String afterId = Long.toUnsignedString(MAX_ID + 100);

        List<Message> result = api.getChannelMessagesAfter(CHANNEL_ID, 0, afterId);

        assertTrue(result.isEmpty());
        assertEquals(1, recordedQueries.size());
        assertTrue(recordedQueries.get(0).contains("after="));
    }

    // === B1: bounded retry, rate-limit and server-error handling ===

    @Test
    void rateLimitedWithRetryAfterHeaderThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/channels/retry-429/messages", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                byte[] body = "{\"message\":\"You are being rate limited.\",\"retry_after\":0.0}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } else {
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });

        List<Message> result = api.getChannelMessages("retry-429", 0, null, null);

        assertTrue(result.isEmpty());
        assertEquals(2, calls.get(), "must retry exactly once after the 429 and then succeed");
    }

    @Test
    void rateLimitedWithNoRetryAfterHintFallsBackToBackoffNotTheCap() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/channels/retry-429-no-hint/messages", exchange -> {
            boolean firstCall = calls.incrementAndGet() == 1;
            // Neither a Retry-After header nor a retry_after body field at all.
            byte[] body = (firstCall ? "{\"message\":\"You are being rate limited.\"}" : "[]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(firstCall ? 429 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        NoSleepDiscordApiService noSleepApi = new NoSleepDiscordApiService(baseUrl());

        List<Message> result = noSleepApi.getChannelMessages("retry-429-no-hint", 0, null, null);

        assertTrue(result.isEmpty());
        assertEquals(2, calls.get(), "must retry exactly once after the 429 and then succeed");
        assertEquals(List.of(1000L), noSleepApi.sleeps,
                "with no Retry-After header or retry_after body, must fall back to attempt-1 backoff "
                        + "(1s) instead of always waiting the full 60s cap");
    }

    @Test
    void serverErrorIsRetriedThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/channels/retry-503/messages", exchange -> {
            boolean firstCall = calls.incrementAndGet() == 1;
            byte[] body = (firstCall ? "Service Unavailable" : "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(firstCall ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        NoSleepDiscordApiService noSleepApi = new NoSleepDiscordApiService(baseUrl());

        List<Message> result = noSleepApi.getChannelMessages("retry-503", 0, null, null);

        assertTrue(result.isEmpty());
        assertEquals(2, calls.get(), "must retry exactly once after the 503 and then succeed");
        assertEquals(List.of(1000L), noSleepApi.sleeps, "must back off ~1s (attempt 1) before retrying");
    }

    @Test
    void retriesAreCappedAndFailureNamesEndpointAndLastStatus() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/channels/retry-capped/messages", exchange -> {
            calls.incrementAndGet();
            byte[] body = "Service Unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        NoSleepDiscordApiService noSleepApi = new NoSleepDiscordApiService(baseUrl());

        IOException ex = assertThrows(IOException.class,
                () -> noSleepApi.getChannelMessages("retry-capped", 0, null, null));

        assertEquals(5, calls.get(), "must give up after exactly the bounded retry cap");
        assertTrue(ex.getMessage().contains("retry-capped"), ex.getMessage());
        assertTrue(ex.getMessage().contains("503"), ex.getMessage());
        assertTrue(ex.getMessage().contains("5 attempts"), ex.getMessage());
    }

    // === B4: archived thread pagination with a since-bound cutoff ===

    @Test
    void archivedPublicThreadsPaginateAndStopOnceASinceBoundIsCrossed() throws Exception {
        // Three pages of two threads each, newest-archived-first, exactly like real Discord
        // archived-thread pagination. sinceBound sits exactly on thread D's archive time: D (equal
        // to the bound) must still be included since the bound is exclusive-of-older, but E (older
        // than the bound) must stop pagination before F is ever fetched.
        String tA = "2024-01-06T00:00:00.000000+00:00";
        String tB = "2024-01-05T00:00:00.000000+00:00";
        String tC = "2024-01-04T00:00:00.000000+00:00";
        String tD = "2024-01-03T00:00:00.000000+00:00";
        String tE = "2024-01-02T00:00:00.000000+00:00";
        String tF = "2024-01-01T00:00:00.000000+00:00";
        Instant sinceBound = Instant.parse("2024-01-03T00:00:00.000000Z");

        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/channels/thread-chan/threads/archived/public", exchange -> {
            calls.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            String before = query == null ? null : parseQuery(query).get("before");
            String body;
            if (before == null) {
                body = threadListJson(List.of(archivedThread("A", tA), archivedThread("B", tB)), true);
            } else if (before.equals(tB)) {
                body = threadListJson(List.of(archivedThread("C", tC), archivedThread("D", tD)), true);
            } else if (before.equals(tD)) {
                body = threadListJson(List.of(archivedThread("E", tE), archivedThread("F", tF)), false);
            } else {
                body = threadListJson(List.of(), false);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });

        List<Channel> result = api.getArchivedPublicThreads("thread-chan", sinceBound);

        assertEquals(List.of("A", "B", "C", "D"), result.stream().map(Channel::id).toList(),
                "must include everything down to (and including) the since-bound, and stop there");
        assertEquals(3, calls.get(), "page 3 must be fetched to discover E crosses the bound, then stop");
    }

    @Test
    void archivedPrivateThreadsForbiddenIsTreatedAsEmptyNotFailure() throws Exception {
        server.createContext("/channels/no-access-chan/threads/archived/private", exchange -> {
            byte[] body = "{\"message\":\"Missing Access\",\"code\":50001}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        List<Channel> result = api.getArchivedPrivateThreads("no-access-chan", null);

        assertTrue(result.isEmpty(), "403 Missing Access must be treated as no private threads, not a failure");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Test seam subclass: records requested sleeps instead of actually sleeping. */
    private static class NoSleepDiscordApiService extends DiscordApiService {
        final List<Long> sleeps = new ArrayList<>();

        NoSleepDiscordApiService(String apiBase) {
            super("fake-token", Duration.ZERO, apiBase);
        }

        @Override
        void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    private static String archivedThread(String id, String archiveTimestamp) {
        return "{\"id\":\"" + id + "\",\"type\":11,\"guild_id\":\"g1\",\"name\":\"thread-" + id + "\","
                + "\"topic\":null,\"position\":0,\"parent_id\":\"thread-chan\",\"last_message_id\":null,"
                + "\"message_count\":0,\"thread_metadata\":{\"archived\":true,\"auto_archive_duration\":1440,"
                + "\"archive_timestamp\":\"" + archiveTimestamp + "\",\"locked\":false}}";
    }

    private static String threadListJson(List<String> threadJsons, boolean hasMore) {
        return "{\"threads\":[" + String.join(",", threadJsons) + "],\"members\":[],\"has_more\":" + hasMore + "}";
    }

    private static List<Long> idsOf(List<Message> messages) {
        return messages.stream().map(m -> Long.parseUnsignedLong(m.id())).toList();
    }

    /**
     * Simulates real Discord {@code before}/{@code after} pagination (never both at once, mirroring
     * production usage): {@code before} returns the {@code limit} messages immediately older than
     * the cursor (or the newest messages when there is no cursor), newest-first; {@code after}
     * returns the {@code limit} messages immediately above the cursor, oldest-above-cursor first
     * but still reported newest-first within that batch. Both bounds are exclusive of the cursor.
     */
    private static String pageResponse(String rawQuery) {
        Map<String, String> params = parseQuery(rawQuery);
        int limit = Integer.parseInt(params.get("limit"));
        String afterParam = params.get("after");
        if (afterParam != null) {
            return toJson(pageAfter(limit, Long.parseUnsignedLong(afterParam)));
        }
        String beforeParam = params.get("before");
        Long cursor = beforeParam != null ? Long.parseUnsignedLong(beforeParam) : null;
        return toJson(page(limit, cursor));
    }

    /**
     * Simulates a misbehaving server that ignores whatever {@code before} it was sent and always
     * returns the newest page — used to verify the client defends itself against a cursor that
     * never advances instead of looping forever or accumulating duplicates.
     */
    private static String misbehavingPageResponse(String rawQuery) {
        Map<String, String> params = parseQuery(rawQuery);
        int limit = Integer.parseInt(params.get("limit"));
        return toJson(page(limit, null));
    }

    /**
     * Extracts the {@code limit} messages immediately older than {@code cursor} (or the newest
     * messages when {@code cursor} is null) from the synthetic channel, newest-first.
     */
    private static List<Long> page(int limit, Long cursor) {
        List<Long> page = new ArrayList<>();
        for (int i = CHANNEL_IDS.size() - 1; i >= 0 && page.size() < limit; i--) {
            long id = CHANNEL_IDS.get(i);
            if (cursor != null && Long.compareUnsigned(id, cursor) >= 0) continue;
            page.add(id);
        }
        return page;
    }

    /**
     * Extracts the {@code limit} messages immediately above {@code cursor} (the oldest ones still
     * above it) from the synthetic channel, reported newest-first within that batch — mirroring
     * real Discord {@code after} semantics.
     */
    private static List<Long> pageAfter(int limit, long cursor) {
        List<Long> page = new ArrayList<>();
        for (int i = 0; i < CHANNEL_IDS.size() && page.size() < limit; i++) {
            long id = CHANNEL_IDS.get(i);
            if (Long.compareUnsigned(id, cursor) <= 0) continue;
            page.add(id);
        }
        Collections.reverse(page);
        return page;
    }

    private static String toJson(List<Long> ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) json.append(',');
            json.append(messageJson(ids.get(i)));
        }
        return json.append(']').toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(pair, "");
            } else {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    /** Mirrors the {@link Message} record's JSON shape (only the fields this test needs). */
    private static String messageJson(long id) {
        return "{\"id\":\"" + Long.toUnsignedString(id) + "\","
                + "\"channel_id\":\"" + CHANNEL_ID + "\","
                + "\"content\":\"message " + id + "\","
                + "\"timestamp\":\"2024-01-01T00:00:00.000000+00:00\","
                + "\"type\":0,"
                + "\"pinned\":false,"
                + "\"mention_everyone\":false}";
    }
}
