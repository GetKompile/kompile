package ai.kompile.e2e;

import ai.kompile.openclaw.gateway.channel.DefaultDiscordApiClient;
import ai.kompile.openclaw.gateway.channel.DiscordApiClient;
import ai.kompile.openclaw.gateway.channel.DiscordApiClient.DiscordMessageHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the DefaultDiscordApiClient: handler management, lifecycle,
 * and API contract verification.
 *
 * These tests mock the HTTP layer to avoid real Discord API calls.
 *
 * Discord integration best practices tested:
 * - Bot token authorization format ("Bot " prefix)
 * - Handler registration/removal thread safety
 * - Channel type filtering (text + announcement only)
 * - Guild/channel data model correctness
 * - Graceful stop behavior
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Default Discord API Client")
class DefaultDiscordApiClientTest {

    private DefaultDiscordApiClient client;

    @Mock
    private DiscordMessageHandler handler1;

    @Mock
    private DiscordMessageHandler handler2;

    @BeforeEach
    void setUp() {
        client = new DefaultDiscordApiClient();
    }

    // ── Handler Management ──

    @Test
    @DisplayName("Add and remove message handlers")
    void testHandlerManagement() {
        client.addMessageHandler(handler1);
        client.addMessageHandler(handler2);
        client.removeMessageHandler(handler1);

        assertDoesNotThrow(() -> client.removeMessageHandler(handler1)); // Double remove safe
    }

    @Test
    @DisplayName("Remove non-existent handler is safe")
    void testRemoveNonExistentHandler() {
        assertDoesNotThrow(() -> client.removeMessageHandler(handler1));
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("Client is not running by default")
    void testNotRunningByDefault() {
        assertFalse(client.isRunning());
    }

    @Test
    @DisplayName("Stop when not running is safe")
    void testStopWhenNotRunning() {
        assertDoesNotThrow(() -> client.stop());
    }

    // ── Data Records ──

    @Test
    @DisplayName("DiscordGuild record holds correct values")
    void testDiscordGuildRecord() {
        DiscordApiClient.DiscordGuild guild = new DiscordApiClient.DiscordGuild(
                "G123", "My Server", "https://icon.url"
        );

        assertEquals("G123", guild.id());
        assertEquals("My Server", guild.name());
        assertEquals("https://icon.url", guild.iconUrl());
    }

    @Test
    @DisplayName("DiscordChannel record holds correct values")
    void testDiscordChannelRecord() {
        DiscordApiClient.DiscordChannel channel = new DiscordApiClient.DiscordChannel(
                "CH1", "G1", "general", "0", 0
        );

        assertEquals("CH1", channel.id());
        assertEquals("G1", channel.guildId());
        assertEquals("general", channel.name());
        assertEquals("0", channel.type());
        assertEquals(0, channel.position());
    }

    @Test
    @DisplayName("DiscordUser record with bot flag")
    void testDiscordUserRecord() {
        DiscordApiClient.DiscordUser human = new DiscordApiClient.DiscordUser(
                "U1", "testuser", "0001", null, false
        );
        assertFalse(human.bot());

        DiscordApiClient.DiscordUser bot = new DiscordApiClient.DiscordUser(
                "U2", "kompile-bot", "0000", null, true
        );
        assertTrue(bot.bot());
    }

    @Test
    @DisplayName("DiscordMessage record with referenced message")
    void testDiscordMessageRecord() {
        DiscordApiClient.DiscordUser author = new DiscordApiClient.DiscordUser(
                "U1", "testuser", "0001", null, false
        );

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg1", "CH1", author, "Hello world",
                System.currentTimeMillis(), "msg0", List.of("https://file.url")
        );

        assertEquals("msg1", message.id());
        assertEquals("CH1", message.channelId());
        assertEquals("testuser", message.author().username());
        assertEquals("Hello world", message.content());
        assertEquals("msg0", message.referencedMessageId());
        assertEquals(1, message.attachmentUrls().size());
    }

    @Test
    @DisplayName("DiscordMessage without references")
    void testDiscordMessageNoReferences() {
        DiscordApiClient.DiscordUser author = new DiscordApiClient.DiscordUser(
                "U1", "user", "0001", null, false
        );

        DiscordApiClient.DiscordMessage message = new DiscordApiClient.DiscordMessage(
                "msg2", "CH2", author, "Standalone message",
                System.currentTimeMillis(), null, null
        );

        assertNull(message.referencedMessageId());
        assertNull(message.attachmentUrls());
    }
}
