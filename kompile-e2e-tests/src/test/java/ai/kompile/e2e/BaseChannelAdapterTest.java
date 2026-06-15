package ai.kompile.e2e;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.channel.BaseChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.*;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for BaseChannelAdapter: lifecycle management, message truncation,
 * session key building, config management, and agent handler creation.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Base Channel Adapter")
class BaseChannelAdapterTest {

    @Mock
    private KClawAgentService agentService;

    @Mock
    private MessageResponder responder;

    private TestChannelAdapter adapter;

    // Concrete test implementation of abstract class
    static class TestChannelAdapter extends BaseChannelAdapter {
        boolean doStartCalled = false;
        boolean doStopCalled = false;

        TestChannelAdapter(KClawAgentService agentService) {
            super(agentService);
        }

        @Override
        public String getChannelName() {
            return "test";
        }

        @Override
        public AdapterConfig getAdapterConfig() {
            return channelConfigs.values().stream().findFirst().orElse(null);
        }

        @Override
        protected void doStart() {
            doStartCalled = true;
        }

        @Override
        protected void doStop() {
            doStopCalled = true;
        }

        // Expose protected methods for testing
        public MessageHandler getAgentHandler() {
            return createAgentHandler();
        }

        public String testBuildSessionKey(AdapterConfig config, IncomingMessage message) {
            return buildSessionKey(config, message);
        }

        public String testTruncateMessage(String content, int maxLength) {
            return truncateMessage(content, maxLength);
        }
    }

    @BeforeEach
    void setUp() {
        adapter = new TestChannelAdapter(agentService);
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("Start calls doStart and sets running")
    void testStart() {
        adapter.start();

        assertTrue(adapter.isRunning());
        assertTrue(adapter.doStartCalled);
    }

    @Test
    @DisplayName("Stop calls doStop and clears running")
    void testStop() {
        adapter.start();
        adapter.stop();

        assertFalse(adapter.isRunning());
        assertTrue(adapter.doStopCalled);
    }

    @Test
    @DisplayName("Double start does not call doStart twice")
    void testDoubleStart() {
        adapter.start();
        adapter.doStartCalled = false;
        adapter.start();

        assertFalse(adapter.doStartCalled);
    }

    @Test
    @DisplayName("Stop when not running does nothing")
    void testStopWhenNotRunning() {
        adapter.stop();

        assertFalse(adapter.doStopCalled);
    }

    // ── Config Management ──

    @Test
    @DisplayName("updateConfig stores config by channel ID")
    void testUpdateConfig() {
        AdapterConfig config = AdapterConfig.defaults("ch-1", "agent-1");
        adapter.updateConfig(config);

        assertEquals(config, adapter.getAdapterConfig());
    }

    @Test
    @DisplayName("AdapterConfig defaults creates config with sensible values")
    void testAdapterConfigDefaults() {
        AdapterConfig config = AdapterConfig.defaults("ch-1", "agent-1");

        assertEquals("ch-1", config.channelId());
        assertEquals("agent-1", config.agentId());
        assertTrue(config.enabled());
        assertEquals("ch-1:", config.sessionKeyPrefix());
        assertEquals(4000, config.maxMessageLength());
        assertFalse(config.allowFileUploads());
        assertFalse(config.allowVoiceMessages());
    }

    // ── Session Key Building ──

    @Test
    @DisplayName("Session key uses prefix + userId")
    void testBuildSessionKey() {
        AdapterConfig config = new AdapterConfig(
                "ch-1", "agent-1", true, "slack:", 4000, false, false
        );
        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "user", "text", "ch-1", 0, null, Map.of()
        );

        String key = adapter.testBuildSessionKey(config, message);

        assertEquals("slack:U123", key);
    }

    @Test
    @DisplayName("Session key with null prefix uses channelId")
    void testBuildSessionKeyNullPrefix() {
        AdapterConfig config = new AdapterConfig(
                "ch-1", "agent-1", true, null, 4000, false, false
        );
        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "user", "text", "ch-1", 0, null, Map.of()
        );

        String key = adapter.testBuildSessionKey(config, message);

        assertEquals("ch-1:U123", key);
    }

    @Test
    @DisplayName("Session key with empty prefix uses channelId")
    void testBuildSessionKeyEmptyPrefix() {
        AdapterConfig config = new AdapterConfig(
                "ch-1", "agent-1", true, "", 4000, false, false
        );
        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "user", "text", "ch-1", 0, null, Map.of()
        );

        String key = adapter.testBuildSessionKey(config, message);

        assertEquals("ch-1:U123", key);
    }

    // ── Message Truncation ──

    @Test
    @DisplayName("Truncate short message returns unchanged")
    void testTruncateShort() {
        assertEquals("hello", adapter.testTruncateMessage("hello", 100));
    }

    @Test
    @DisplayName("Truncate long message adds ellipsis")
    void testTruncateLong() {
        String result = adapter.testTruncateMessage("abcdefghij", 5);
        assertEquals("abcde...", result);
    }

    @Test
    @DisplayName("Truncate null returns empty string")
    void testTruncateNull() {
        assertEquals("", adapter.testTruncateMessage(null, 100));
    }

    @Test
    @DisplayName("Truncate with zero max returns original")
    void testTruncateZeroMax() {
        assertEquals("hello", adapter.testTruncateMessage("hello", 0));
    }

    @Test
    @DisplayName("Truncate message at exact limit")
    void testTruncateExactLimit() {
        assertEquals("hello", adapter.testTruncateMessage("hello", 5));
    }

    // ── Agent Handler ──

    @Test
    @DisplayName("Agent handler processes message and calls responder")
    void testAgentHandlerSuccess() {
        AdapterConfig config = AdapterConfig.defaults("ch-1", "test-agent");
        adapter.updateConfig(config);

        AgentResponse agentResponse = AgentResponse.builder()
                .response("Agent reply")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "User123", "Hello agent",
                "ch-1", System.currentTimeMillis(), null, Map.of()
        );

        adapter.getAgentHandler().handle(message, responder);

        verify(responder).typing();
        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).execute(reqCaptor.capture());
        assertEquals("test-agent", reqCaptor.getValue().getAgentId());
        assertTrue(reqCaptor.getValue().getMessage().contains("Hello agent"));
        ArgumentCaptor<OutgoingMessage> outCaptor = ArgumentCaptor.forClass(OutgoingMessage.class);
        verify(responder).reply(outCaptor.capture());
        assertEquals("Agent reply", outCaptor.getValue().content());
    }

    @Test
    @DisplayName("Agent handler sends error on failed response")
    void testAgentHandlerFailedResponse() {
        AdapterConfig config = AdapterConfig.defaults("ch-1", "test-agent");
        adapter.updateConfig(config);

        AgentResponse errorResponse = AgentResponse.builder()
                .success(false)
                .error("Rate limited")
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(errorResponse);

        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "User123", "Hello",
                "ch-1", System.currentTimeMillis(), null, Map.of()
        );

        adapter.getAgentHandler().handle(message, responder);

        verify(responder).replyError("Rate limited");
    }

    @Test
    @DisplayName("Agent handler sends internal error on exception")
    void testAgentHandlerException() {
        AdapterConfig config = AdapterConfig.defaults("ch-1", "test-agent");
        adapter.updateConfig(config);

        when(agentService.execute(any(AgentRequest.class))).thenThrow(new RuntimeException("Crash"));

        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "User123", "Hello",
                "ch-1", System.currentTimeMillis(), null, Map.of()
        );

        adapter.getAgentHandler().handle(message, responder);

        verify(responder).replyError("Internal error processing your request");
    }

    @Test
    @DisplayName("Agent handler skips disabled channel config")
    void testAgentHandlerDisabledConfig() {
        AdapterConfig config = new AdapterConfig(
                "ch-1", "test-agent", false, "ch-1:", 4000, false, false
        );
        adapter.updateConfig(config);

        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "User123", "Hello",
                "ch-1", System.currentTimeMillis(), null, Map.of()
        );

        adapter.getAgentHandler().handle(message, responder);

        verify(agentService, never()).execute(any());
        verify(responder, never()).reply(any());
    }

    @Test
    @DisplayName("Agent handler skips message with no matching config")
    void testAgentHandlerNoConfig() {
        IncomingMessage message = new IncomingMessage(
                "msg1", "U123", "User123", "Hello",
                "unknown-channel", System.currentTimeMillis(), null, Map.of()
        );

        adapter.getAgentHandler().handle(message, responder);

        verify(agentService, never()).execute(any());
    }

    // ── OutgoingMessage Factory ──

    @Test
    @DisplayName("OutgoingMessage.text creates simple message")
    void testOutgoingMessageText() {
        OutgoingMessage msg = OutgoingMessage.text("Hello");

        assertEquals("Hello", msg.content());
        assertNull(msg.replyToId());
        assertNull(msg.attachments());
    }

    @Test
    @DisplayName("OutgoingMessage.reply creates reply message")
    void testOutgoingMessageReply() {
        OutgoingMessage msg = OutgoingMessage.reply("Reply text", "msg1");

        assertEquals("Reply text", msg.content());
        assertEquals("msg1", msg.replyToId());
    }
}
