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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsInfoRequest;
import com.slack.api.methods.request.conversations.ConversationsListRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.Message;
import com.slack.api.model.ResponseMetadata;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlackLoaderImpl} covering the 2026-09-28 audit fixes: thread-reply
 * fetching under a shared total cap, actionable errors instead of silent swallowing,
 * channel-list cursor pagination, rate-limit retry with backoff, and the since→oldest
 * mapping. The Slack SDK's {@link MethodsClient} is mocked throughout; no network calls
 * are made.
 */
class SlackLoaderImplTest {

    private MethodsClient client;
    private SlackLoaderImpl loader;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(MethodsClient.class);
        loader = new SlackLoaderImpl();
        loader.setClientForTesting(client);
        loader.setRetrySleeperForTesting(millis -> { });

        // conversations.info is called for every resolved channel; default to a benign
        // response unless an individual test overrides it.
        when(client.conversationsInfo(any(ConversationsInfoRequest.class))).thenReturn(infoResponse("general"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private DocumentSourceDescriptor descriptor(String channel, Map<String, Object> metadata) {
        metadata.putIfAbsent("slackToken", "test-token");
        return DocumentSourceDescriptor.builder()
                .type(SourceType.SLACK)
                .pathOrUrl(channel)
                .metadata(metadata)
                .build();
    }

    private Message message(String ts, String threadTs, String text, Integer replyCount) {
        Message message = new Message();
        message.setTs(ts);
        message.setThreadTs(threadTs);
        message.setUser("U1");
        message.setText(text);
        message.setReplyCount(replyCount);
        return message;
    }

    private ConversationsHistoryResponse historyResponse(List<Message> messages, String nextCursor) {
        ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setOk(true);
        response.setMessages(messages);
        response.setResponseMetadata(responseMetadata(nextCursor));
        return response;
    }

    private ConversationsHistoryResponse errorHistoryResponse(String error, String needed) {
        ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setOk(false);
        response.setError(error);
        response.setNeeded(needed);
        return response;
    }

    private ConversationsRepliesResponse repliesResponse(List<Message> messages) {
        ConversationsRepliesResponse response = new ConversationsRepliesResponse();
        response.setOk(true);
        response.setMessages(messages);
        return response;
    }

    private ConversationsInfoResponse infoResponse(String name) {
        ConversationsInfoResponse response = new ConversationsInfoResponse();
        response.setOk(true);
        Conversation channel = new Conversation();
        channel.setName(name);
        response.setChannel(channel);
        return response;
    }

    private ConversationsListResponse listResponse(List<Conversation> channels, String nextCursor) {
        ConversationsListResponse response = new ConversationsListResponse();
        response.setOk(true);
        response.setChannels(channels);
        response.setResponseMetadata(responseMetadata(nextCursor));
        return response;
    }

    private ResponseMetadata responseMetadata(String nextCursor) {
        if (nextCursor == null) {
            return null;
        }
        ResponseMetadata meta = new ResponseMetadata();
        meta.setNextCursor(nextCursor);
        return meta;
    }

    private Conversation channel(String id, String name) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private SlackApiException rateLimitException(String retryAfterSeconds) {
        Request request = new Request.Builder().url("https://slack.com/api/conversations.history").build();
        Response.Builder responseBuilder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests");
        if (retryAfterSeconds != null) {
            responseBuilder.header("Retry-After", retryAfterSeconds);
        }
        return new SlackApiException(responseBuilder.build(), "{\"ok\":false,\"error\":\"ratelimited\"}");
    }

    // ── identity ─────────────────────────────────────────────────────────

    @Test
    void nameReturnsExpectedValue() {
        assertEquals("Slack Channel Loader", loader.getName());
    }

    @Test
    void supportsSlackSourceType() {
        assertTrue(loader.supports(DocumentSourceDescriptor.builder().type(SourceType.SLACK).build()));
    }

    @Test
    void doesNotSupportOtherSourceTypes() {
        assertFalse(loader.supports(DocumentSourceDescriptor.builder().type(SourceType.URL).build()));
    }

    // ── required inputs ──────────────────────────────────────────────────

    @Test
    void loadThrowsWhenTokenMissing() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.SLACK)
                .pathOrUrl("C123")
                .build();
        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void loadThrowsWhenChannelMissing() {
        DocumentSourceDescriptor descriptor = descriptor(null, new HashMap<>());
        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    // ── messages + source_path (C-5) ─────────────────────────────────────

    @Test
    void loadReturnsMessagesWithSourcePathAndSkipsThreadFetchWhenNoReplies() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(
                        message("1111111.000100", null, "hello", 0),
                        message("2222222.000200", null, "world", 0)), null));

        List<Document> docs = loader.load(descriptor("C123", new HashMap<>()));

        assertEquals(2, docs.size());
        assertEquals("slack://channel/C123/message/1111111.000100", docs.get(0).getMetadata().get("source_path"));
        assertEquals("slack://channel/C123/message/2222222.000200", docs.get(1).getMetadata().get("source_path"));
        assertEquals(Boolean.FALSE, docs.get(0).getMetadata().get("is_thread_reply"));
        verify(client, never()).conversationsReplies(any(ConversationsRepliesRequest.class));
    }

    // ── thread replies + total cap (C-1, C-6) ────────────────────────────

    @Test
    void loadFetchesRepliesAndCountsThemTowardTheTotalCap() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(
                        message("1111111.000100", "1111111.000100", "parent", 2)), null));
        when(client.conversationsReplies(any(ConversationsRepliesRequest.class)))
                .thenReturn(repliesResponse(List.of(
                        message("1111111.000100", "1111111.000100", "parent", 2),
                        message("1111111.000200", "1111111.000100", "reply1", null),
                        message("1111111.000300", "1111111.000100", "reply2", null))));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("limit", 2);
        List<Document> docs = loader.load(descriptor("C123", metadata));

        assertEquals(2, docs.size(), "the total cap must include replies, not just primary messages");
        assertEquals("1111111.000100", docs.get(0).getMetadata().get("message_ts"));
        assertEquals("1111111.000200", docs.get(1).getMetadata().get("message_ts"));
        assertEquals(Boolean.TRUE, docs.get(1).getMetadata().get("is_thread_reply"));
        assertEquals("slack://channel/C123/message/1111111.000100", docs.get(1).getMetadata().get("thread_parent_source_path"));
    }

    // ── limit parsing (C-2) ──────────────────────────────────────────────

    @Test
    void nonPositiveLimitFallsBackToDefault() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(), null));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("limit", 0);
        loader.load(descriptor("C123", metadata));

        ArgumentCaptor<ConversationsHistoryRequest> captor = ArgumentCaptor.forClass(ConversationsHistoryRequest.class);
        verify(client).conversationsHistory(captor.capture());
        assertEquals(Integer.valueOf(100), captor.getValue().getLimit());
    }

    @Test
    void limitAcceptsNumericStringValue() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(message("1.1", null, "a", 0)), null));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("limit", "1");
        List<Document> docs = loader.load(descriptor("C123", metadata));

        assertEquals(1, docs.size());
        ArgumentCaptor<ConversationsHistoryRequest> captor = ArgumentCaptor.forClass(ConversationsHistoryRequest.class);
        verify(client).conversationsHistory(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().getLimit());
    }

    // ── API errors (C-2) ─────────────────────────────────────────────────

    @Test
    void apiErrorsProduceActionableExceptionMessages() throws Exception {
        Map<String, String> codeToHint = Map.of(
                "not_in_channel", "invite",
                "channel_not_found", "channel id/name",
                "invalid_auth", "reconnect",
                "token_revoked", "reconnect",
                "not_authed", "reconnect",
                "account_inactive", "reconnect");

        for (Map.Entry<String, String> entry : codeToHint.entrySet()) {
            MethodsClient errorClient = mock(MethodsClient.class);
            when(errorClient.conversationsInfo(any(ConversationsInfoRequest.class))).thenReturn(infoResponse("general"));
            when(errorClient.conversationsHistory(any(ConversationsHistoryRequest.class)))
                    .thenReturn(errorHistoryResponse(entry.getKey(), null));

            SlackLoaderImpl errorLoader = new SlackLoaderImpl();
            errorLoader.setClientForTesting(errorClient);
            errorLoader.setRetrySleeperForTesting(millis -> { });

            DocumentSourceDescriptor descriptor = descriptor("C123", new HashMap<>());
            Exception ex = assertThrows(IllegalStateException.class, () -> errorLoader.load(descriptor),
                    "expected failure for error code " + entry.getKey());
            assertTrue(ex.getMessage().contains(entry.getKey()), ex.getMessage());
            assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains(entry.getValue()), ex.getMessage());
        }
    }

    @Test
    void missingScopeErrorIncludesNeededScopes() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(errorHistoryResponse("missing_scope", "channels:history"));

        DocumentSourceDescriptor descriptor = descriptor("C123", new HashMap<>());
        Exception ex = assertThrows(IllegalStateException.class, () -> loader.load(descriptor));
        assertTrue(ex.getMessage().contains("channels:history"), ex.getMessage());
    }

    // ── rate limiting (C-2) ──────────────────────────────────────────────

    @Test
    void rateLimitedCallsRetryAndEventuallySucceed() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenThrow(rateLimitException("0"))
                .thenThrow(rateLimitException("0"))
                .thenReturn(historyResponse(List.of(message("1111111.000100", null, "hi", 0)), null));

        List<Document> docs = loader.load(descriptor("C123", new HashMap<>()));

        assertEquals(1, docs.size());
        verify(client, times(3)).conversationsHistory(any(ConversationsHistoryRequest.class));
    }

    @Test
    void rateLimitedCallsGiveUpAfterMaxAttempts() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenThrow(rateLimitException("0"));

        DocumentSourceDescriptor descriptor = descriptor("C123", new HashMap<>());
        assertThrows(SlackApiException.class, () -> loader.load(descriptor));
        verify(client, times(5)).conversationsHistory(any(ConversationsHistoryRequest.class));
    }

    @Test
    void retryAfterHeaderIsCappedAtSixtyThousandMillis() throws Exception {
        long[] sleptMillis = new long[1];
        loader.setRetrySleeperForTesting(millis -> sleptMillis[0] = millis);

        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenThrow(rateLimitException("3600"))
                .thenReturn(historyResponse(List.of(message("1111111.000100", null, "hi", 0)), null));

        loader.load(descriptor("C123", new HashMap<>()));

        assertEquals(60_000L, sleptMillis[0], "a huge Retry-After must not stall the crawl for minutes/hours");
    }

    @Test
    void interruptedSleepStopsRetryingAfterTheFirstAttempt() throws Exception {
        loader.setRetrySleeperForTesting(millis -> {
            throw new InterruptedException("test interrupt");
        });

        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenThrow(rateLimitException("0"));

        DocumentSourceDescriptor descriptor = descriptor("C123", new HashMap<>());
        try {
            assertThrows(InterruptedIOException.class, () -> loader.load(descriptor));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
            verify(client, times(1)).conversationsHistory(any(ConversationsHistoryRequest.class));
        } finally {
            Thread.interrupted(); // clear the flag so it doesn't leak into other tests
        }
    }

    // ── channel-list pagination (C-3) ────────────────────────────────────

    @Test
    void resolveChannelByNameFollowsCursorPagination() throws Exception {
        when(client.conversationsList(any(ConversationsListRequest.class)))
                .thenReturn(listResponse(List.of(channel("C001", "random")), "cursor-1"))
                .thenReturn(listResponse(List.of(channel("C999", "general")), null));
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(message("1111111.000100", null, "hi", 0)), null));

        List<Document> docs = loader.load(descriptor("general", new HashMap<>()));

        assertEquals("C999", docs.get(0).getMetadata().get("channel_id"));
        verify(client, times(2)).conversationsList(any(ConversationsListRequest.class));
    }

    // ── since → oldest (C-3, C-4) ────────────────────────────────────────

    @Test
    void sinceIsMappedToOldestWhenNoExplicitOldestGiven() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(), null));

        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("since", since.toString());
        loader.load(descriptor("C123", metadata));

        ArgumentCaptor<ConversationsHistoryRequest> captor = ArgumentCaptor.forClass(ConversationsHistoryRequest.class);
        verify(client).conversationsHistory(captor.capture());
        assertEquals(String.valueOf(since.getEpochSecond()), captor.getValue().getOldest());
    }

    @Test
    void laterOfExplicitOldestAndSinceWins() throws Exception {
        when(client.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyResponse(List.of(), null));

        Instant since = Instant.parse("2026-06-01T00:00:00Z"); // later than the explicit "oldest" below
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("oldest", "1000000");
        metadata.put("since", since.toString());
        loader.load(descriptor("C123", metadata));

        ArgumentCaptor<ConversationsHistoryRequest> captor = ArgumentCaptor.forClass(ConversationsHistoryRequest.class);
        verify(client).conversationsHistory(captor.capture());
        assertEquals(String.valueOf(since.getEpochSecond()), captor.getValue().getOldest());
    }
}
