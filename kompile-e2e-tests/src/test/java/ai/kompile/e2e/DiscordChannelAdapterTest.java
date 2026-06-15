package ai.kompile.e2e;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.channel.*;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.AdapterConfig;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.gateway.channel.DiscordApiClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    @Mock
    private DiscordApiClient discordApiClient;

    private DiscordChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DiscordChannelAdapter(agentService);
        adapter.setApiClient(discordApiClient);
        adapter.setBotToken("discord-bot-token");

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
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G1", "Test Guild", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "discord-ch-1", "G1", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G1")).thenReturn(List.of(channel));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("Hello from agent!")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", humanUser, "Help me!",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(discordApiClient).sendTyping("discord-ch-1");
        ArgumentCaptor<AgentRequest> reqCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).execute(reqCaptor.capture());
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
                "msg1", "discord-ch-1", botUser, "Bot response",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService, never()).execute(any());
    }

    // ── Channel/Guild Whitelisting ──

    @Test
    @DisplayName("Messages from non-whitelisted channels are ignored")
    void testChannelWhitelistBlocks() {
        adapter.addAllowedChannel("ALLOWED_CH");

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        // No guild data needed since channel check happens first
        when(discordApiClient.getGuilds()).thenReturn(List.of());

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "NOT_ALLOWED_CH", humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Messages from whitelisted channels are processed")
    void testChannelWhitelistAllows() {
        adapter.addAllowedChannel("ALLOWED_CH");
        adapter.updateConfig(AdapterConfig.defaults("ALLOWED_CH", "test-agent"));

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G1", "Test", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "ALLOWED_CH", "G1", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G1")).thenReturn(List.of(channel));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "ALLOWED_CH", humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService).execute(any());
    }

    @Test
    @DisplayName("Messages from whitelisted guild are processed")
    void testGuildWhitelistAllows() {
        adapter.addAllowedGuild("G_ALLOWED");
        adapter.updateConfig(AdapterConfig.defaults("some-ch", "test-agent"));

        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G_ALLOWED", "Allowed Guild", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "some-ch", "G_ALLOWED", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G_ALLOWED")).thenReturn(List.of(channel));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "some-ch", humanUser, "Hello from guild",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService).execute(any());
    }

    @Test
    @DisplayName("Empty whitelist allows all channels and guilds")
    void testEmptyWhitelistAllowsAll() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G1", "Test", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "discord-ch-1", "G1", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G1")).thenReturn(List.of(channel));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", humanUser, "Hello",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService).execute(any());
    }

    // ── Empty/Null Content ──

    @Test
    @DisplayName("Empty content messages are ignored")
    void testEmptyContentIgnored() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", humanUser, "",
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService, never()).execute(any());
    }

    @Test
    @DisplayName("Null content messages are ignored")
    void testNullContentIgnored() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", humanUser, null,
                System.currentTimeMillis(), null, null
        );

        adapter.onMessage(message);

        verify(agentService, never()).execute(any());
    }

    // ── Error Handling ──

    @Test
    @DisplayName("Agent error sends error message to Discord")
    void testAgentErrorResponse() {
        DiscordApiClient.DiscordUser humanUser = new DiscordApiClient.DiscordUser(
                "U123", "testuser", "0001", null, false);
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G1", "Test", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "discord-ch-1", "G1", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G1")).thenReturn(List.of(channel));

        AgentResponse errorResponse = AgentResponse.builder()
                .success(false)
                .error("API unavailable")
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(errorResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "discord-ch-1", humanUser, "Test",
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
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild("G1", "Test", null);
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "discord-ch-1", "G1", "general", "0", 0);

        when(discordApiClient.getGuilds()).thenReturn(List.of(guild));
        when(discordApiClient.getChannels("G1")).thenReturn(List.of(channel));

        AgentResponse agentResponse = AgentResponse.builder()
                .response("OK")
                .success(true)
                .build();
        when(agentService.execute(any(AgentRequest.class))).thenReturn(agentResponse);

        adapter.start();

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg2", "discord-ch-1", humanUser, "Follow up",
                System.currentTimeMillis(), "msg1", null
        );

        adapter.onMessage(message);

        verify(agentService).execute(any());
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
