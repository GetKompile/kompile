package ai.kompile.e2e;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.channel.*;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.AdapterConfig;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.IncomingMessage;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.gateway.channel.SlackApiClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Slack channel adapter: message receiving, bot filtering,
 * mention handling, channel whitelisting, and agent routing.
 *
 * Best practices for Slack bot testing:
 * - Mock the SlackApiClient to avoid real HTTP calls
 * - Test both @mention and all-messages modes
 * - Verify bot messages are filtered (prevent infinite loops)
 * - Verify @mention tags are cleaned from message text
 * - Test channel whitelisting
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Slack Channel Adapter")
class SlackChannelAdapterTest {

    @Mock
    private KClawAgentService agentService;

    @Mock
    private SlackApiClient slackApiClient;

    private SlackChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SlackChannelAdapter(agentService);
        adapter.setApiClient(slackApiClient);
        adapter.setBotToken("xoxb-test-token");
        adapter.setAppToken("xapp-test-token");

        // Configure a channel
        AdapterConfig config = AdapterConfig.defaults("C12345", "test-agent");
        adapter.updateConfig(config);
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("Start registers handler and starts API client")
    void testStart() {
        adapter.start();

        assertTrue(adapter.isRunning());
        verify(slackApiClient).addMessageHandler(adapter);
        verify(slackApiClient).start("xoxb-test-token", "xapp-test-token");
    }

    @Test
    @DisplayName("Start without API client does not throw")
    void testStartWithoutApiClient() {
        SlackChannelAdapter adapterNoClient = new SlackChannelAdapter(agentService);
        adapterNoClient.start();

        assertTrue(adapterNoClient.isRunning());
    }

    @Test
    @DisplayName("Stop removes handler and stops API client")
    void testStop() {
        adapter.start();
        adapter.stop();

        assertFalse(adapter.isRunning());
        verify(slackApiClient).removeMessageHandler(adapter);
        verify(slackApiClient).stop();
    }

    @Test
    @DisplayName("Double start is idempotent")
    void testDoubleStart() {
        adapter.start();
        adapter.start();

        verify(slackApiClient, times(1)).start(anyString(), anyString());
    }

    @Test
    @DisplayName("Channel name is slack")
    void testChannelName() {
        assertEquals("slack", adapter.getChannelName());
    }

    // ── Message Reception ──

    @Test
    @DisplayName("App mention triggers agent execution")
    void testAppMentionTriggersAgent() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("I can help with that!")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "1234567890.123456", "C12345", "U999", "testuser",
                "<@UBOTID> help me with something", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(slackApiClient).sendTyping("C12345");
        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).execute(reqCaptor.capture());
        assertEquals("test-agent", reqCaptor.getValue().getAgentId());
        assertEquals("help me with something", reqCaptor.getValue().getMessage());
        verify(slackApiClient).sendMessage(eq("C12345"), eq("I can help with that!"), eq("1234567890.123456"));
    }

    @Test
    @DisplayName("Regular message is ignored when respondToAllMessages is false")
    void testRegularMessageIgnoredByDefault() {
        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "Hello there", null, null, null, null
        );

        adapter.onMessage(message);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Regular message is processed when respondToAllMessages is true")
    void testRegularMessageProcessedWhenEnabled() {
        adapter.setRespondToAllMessages(true);

        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("Reply")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "Hello there", null, null, null, null
        );

        adapter.onMessage(message);

        verify(agentService).execute(any());
    }

    // ── Bot Message Filtering ──

    @Test
    @DisplayName("Bot subtype messages are filtered out")
    void testBotSubtypeFiltered() {
        adapter.setRespondToAllMessages(true);
        adapter.start();

        SlackApiClient.SlackMessage botMessage = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "UBOT", "bot",
                "I am a bot", null, "bot_message", null, null
        );

        adapter.onAppMention(botMessage);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Messages from bot users are filtered out")
    void testBotUserFiltered() {
        SlackApiClient.SlackUser botUser = new SlackApiClient.SlackUser(
                "UBOT", "mybot", "My Bot", null, true);
        when(slackApiClient.getUsers()).thenReturn(List.of(botUser));

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "UBOT", "mybot",
                "<@UBOTID> test", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService, never()).execute(any());
    }

    // ── Channel Whitelisting ──

    @Test
    @DisplayName("Messages from non-whitelisted channels are ignored")
    void testChannelWhitelistBlocks() {
        adapter.addAllowedChannel("C_ALLOWED");

        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C_NOT_ALLOWED", "U999", "testuser",
                "<@BOT> hello", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Messages from whitelisted channels are processed")
    void testChannelWhitelistAllows() {
        adapter.addAllowedChannel("C_ALLOWED");

        // Need config for the allowed channel
        adapter.updateConfig(AdapterConfig.defaults("C_ALLOWED", "test-agent"));

        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C_ALLOWED", "U999", "testuser",
                "<@BOT> hello", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService).execute(any());
    }

    @Test
    @DisplayName("Empty whitelist allows all channels")
    void testEmptyWhitelistAllowsAll() {
        // No addAllowedChannel calls = empty whitelist = all allowed
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "<@BOT> hello", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService).execute(any());
    }

    // ── Mention Cleaning ──

    @Test
    @DisplayName("Mention tags are cleaned from message text")
    void testMentionCleaning() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "<@U123BOT> <#C456|general> what is the status?", null, null, null, null
        );

        adapter.onAppMention(message);

        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).execute(reqCaptor.capture());
        assertEquals("what is the status?", reqCaptor.getValue().getMessage());
    }

    // ── Empty/Null Message Handling ──

    @Test
    @DisplayName("Empty text messages are ignored")
    void testEmptyTextIgnored() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Null text messages are ignored")
    void testNullTextIgnored() {
        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                null, null, null, null, null
        );

        adapter.onAppMention(message);

        verify(agentService, never()).execute(any());
    }

    // ── Error Response Handling ──

    @Test
    @DisplayName("Agent error response sends error message to Slack")
    void testAgentErrorResponse() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse errorResponse = AgentResponse.builder()
                .success(false)
                .error("Rate limit exceeded")
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(errorResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "<@BOT> test", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(slackApiClient).sendMessage(eq("C12345"), contains("Error: Rate limit exceeded"), eq("ts1"));
    }

    @Test
    @DisplayName("Agent exception sends internal error message to Slack")
    void testAgentExceptionSendsError() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        when(agentService.execute(any(AgentRequest.class))).thenThrow(new RuntimeException("Internal failure"));

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "testuser",
                "<@BOT> test", null, null, null, null
        );

        adapter.onAppMention(message);

        verify(slackApiClient).sendMessage(eq("C12345"), contains("Error:"), eq("ts1"));
    }

    // ── Thread Support ──

    @Test
    @DisplayName("Thread timestamp is used for reply")
    void testThreadReply() {
        SlackApiClient.SlackUser humanUser = new SlackApiClient.SlackUser(
                "U999", "testuser", "Test User", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(humanUser));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("Thread reply")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        // Message in a thread
        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "1234567890.123456", "C12345", "U999", "testuser",
                "<@BOT> reply in thread", "1234567890.000000", null, null, null
        );

        adapter.onAppMention(message);

        // Reply should use the original message timestamp (ts), not threadTs
        verify(slackApiClient).sendMessage(eq("C12345"), eq("Thread reply"), eq("1234567890.123456"));
    }

    // ── User Resolution ──

    @Test
    @DisplayName("User name is resolved from users list")
    void testUserNameResolution() {
        SlackApiClient.SlackUser user = new SlackApiClient.SlackUser(
                "U999", "jdoe", "Jane Doe", null, false);
        when(slackApiClient.getUsers()).thenReturn(List.of(user));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        SlackApiClient.SlackMessage message = new SlackApiClient.SlackMessage(
                "ts1", "C12345", "U999", "jdoe",
                "<@BOT> hello", null, null, null, null
        );

        adapter.onAppMention(message);

        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).execute(reqCaptor.capture());
        assertEquals("Jane Doe", reqCaptor.getValue().getMetadata().get("userName"));
    }

    // ── Event Callbacks ──

    @Test
    @DisplayName("onReady callback does not throw")
    void testOnReady() {
        assertDoesNotThrow(() -> adapter.onReady());
    }

    @Test
    @DisplayName("onError callback does not throw")
    void testOnError() {
        assertDoesNotThrow(() -> adapter.onError(new RuntimeException("test")));
    }
}
