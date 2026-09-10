package ai.kompile.e2e;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.channel.*;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.AdapterConfig;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.gateway.channel.DiscordApiClient;
import ai.kompile.gateway.core.service.AgentExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Discord channel adapter: message receiving, bot filtering,
 * guild/channel whitelisting, and agent routing.
 *
 * Best practices for Discord bot testing:
 * - Mock the DiscordApiClient (avoids real WebSocket/HTTP)
 * - Verify bot messages are skipped (author.bot() = true)
 * - Test guild and channel whitelisting
 * - Test empty/null content filtering
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Discord Channel Adapter")
class DiscordChannelAdapterTest {

    @Mock
    private KClawAgentService agentService;

    // Upcast to AgentExecutor to disambiguate execute() overloads in when()/verify() calls
    private AgentExecutor agentExecutor;

    @Mock
    private DiscordApiClient discordApiClient;

    private DiscordChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        agentExecutor = agentService;
        adapter = new DiscordChannelAdapter(agentService);
        adapter.setApiClient(discordApiClient);
        adapter.setBotToken("discord-bot-token");
        adapter.addAllowedChannel("discord-ch-1");

        AdapterConfig config = AdapterConfig.defaults("discord-ch-1", "test-agent");
        adapter.updateConfig(config);
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("Start registers handler and starts client")
    void testStart() {
        adapter.start();

        assertTrue(adapter.isRunning());
        verify(discordApiClient).addMessageHandler(adapter);
        verify(discordApiClient).start("discord-bot-token");
    }

    @Test
    @DisplayName("Stop removes handler and stops client")
    void testStop() {
        adapter.start();
        adapter.stop();

        assertFalse(adapter.isRunning());
        verify(discordApiClient).removeMessageHandler(adapter);
        verify(discordApiClient).stop();
    }

    @Test
    @DisplayName("Channel name is discord")
    void testChannelName() {
        assertEquals("discord", adapter.getChannelName());
    }

    // ── Message Reception ──

    @Test
    @DisplayName("Human message triggers agent execution")
    void testHumanMessageTriggersAgent() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        AgentResponse agentResponse = AgentResponse.builder()
                .response("Hello from agent!")
                .success(true)
                .build();
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", "G1", humanUser, "Help me!",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(discordApiClient).sendTyping("discord-ch-1");
        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentExecutor).execute(reqCaptor.capture());
        assertEquals("test-agent", reqCaptor.getValue().getAgentId());
        assertEquals("Help me!", reqCaptor.getValue().getMessage());
        verify(discordApiClient).sendMessage(eq("discord-ch-1"), eq("Hello from agent!"));
    }

    // ── Bot Filtering ──

    @Test
    @DisplayName("Bot author messages are filtered out")
    void testBotMessageFiltered() {
        DiscordApiClient.DiscordUser botUser = new DiscordApiClient.DiscordUser(
                "UBOT", "kompile-bot", "0000", null, true);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", null, botUser, "Bot response",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor, never()).execute(any(AgentRequest.class));
    }

    // ── Channel/Guild Whitelisting ──

    @Test
    @DisplayName("Messages from non-whitelisted channels are ignored")
    void testChannelWhitelistBlocks() {
        adapter.addAllowedChannel("ALLOWED_CH");

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "NOT_ALLOWED_CH", null, humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor, never()).execute(any(AgentRequest.class));
    }

    @Test
    @DisplayName("Messages from whitelisted channels are processed")
    void testChannelWhitelistAllows() {
        adapter.addAllowedChannel("ALLOWED_CH");
        adapter.updateConfig(AdapterConfig.defaults("ALLOWED_CH", "test-agent"));

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "ALLOWED_CH", "G1", humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor).execute(any(AgentRequest.class));
    }

    @Test
    @DisplayName("Messages from whitelisted guild are processed")
    void testGuildWhitelistAllows() {
        adapter.addAllowedGuild("G_ALLOWED");
        adapter.updateConfig(AdapterConfig.defaults("some-ch", "test-agent"));

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "some-ch", "G_ALLOWED", humanUser, "Hello from guild",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor).execute(any(AgentRequest.class));
    }

    @Test
    @DisplayName("Empty whitelist denies inbound channels and guilds")
    void testEmptyWhitelistDeniesAll() {
        DiscordChannelAdapter denyByDefault = new DiscordChannelAdapter(agentService);
        denyByDefault.setApiClient(discordApiClient);
        denyByDefault.setBotToken("discord-bot-token");
        denyByDefault.updateConfig(AdapterConfig.defaults("discord-ch-1", "test-agent"));
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        denyByDefault.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", "G1", humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        denyByDefault.onMessage(message);

        verify(agentExecutor, never()).execute(any(AgentRequest.class));
    }

    // ── Empty/Null Content ──

    @Test
    @DisplayName("Empty content messages are ignored")
    void testEmptyContentIgnored() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", null, humanUser, "",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor, never()).execute(any(AgentRequest.class));
    }

    @Test
    @DisplayName("Null content messages are ignored")
    void testNullContentIgnored() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", null, humanUser, null,
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentExecutor, never()).execute(any(AgentRequest.class));
    }

    // ── Error Handling ──

    @Test
    @DisplayName("Agent error sends error message to Discord")
    void testAgentErrorResponse() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        AgentResponse errorResponse = AgentResponse.builder()
                .success(false)
                .error("API unavailable")
                .build();
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(errorResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", "G1", humanUser, "Test",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(discordApiClient).sendMessage(eq("discord-ch-1"), contains("Error: API unavailable"));
    }

    // ── Referenced Messages (Thread support) ──

    @Test
    @DisplayName("Referenced message ID is passed as replyToId")
    void testReferencedMessage() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg2", "discord-ch-1", "G1", humanUser, "Follow up",
                System.currentTimeMillis(), "msg1", null
        );

        adapter.onMessage(message);

        verify(agentExecutor).execute(any(AgentRequest.class));
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
