package ai.kompile.e2e;

import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter.AdapterConfig;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.gateway.channel.ChannelManager.ChannelStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ChannelManager: adapter registration, lifecycle management,
 * status reporting, and error isolation.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Channel Manager")
class ChannelManagerTest {

    private ChannelManager channelManager;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
    }

    private ChannelAdapter mockAdapter(String name, boolean running) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        lenient().when(adapter.getChannelName()).thenReturn(name);
        lenient().when(adapter.isRunning()).thenReturn(running);
        lenient().when(adapter.getAdapterConfig()).thenReturn(AdapterConfig.defaults(name, "agent-1"));
        return adapter;
    }

    // ── Registration ──

    @Test
    @DisplayName("Register adapter makes it retrievable")
    void testRegisterAdapter() {
        ChannelAdapter adapter = mockAdapter("slack", false);
        channelManager.registerAdapter(adapter);

        Optional<ChannelAdapter> result = channelManager.getAdapter("slack");
        assertTrue(result.isPresent());
        assertEquals("slack", result.get().getChannelName());
    }

    @Test
    @DisplayName("Unregister adapter stops and removes it")
    void testUnregisterAdapter() {
        ChannelAdapter adapter = mockAdapter("slack", true);
        channelManager.registerAdapter(adapter);
        channelManager.unregisterAdapter("slack");

        verify(adapter).stop();
        assertTrue(channelManager.getAdapter("slack").isEmpty());
    }

    @Test
    @DisplayName("Unregister non-existent adapter does not throw")
    void testUnregisterNonExistent() {
        assertDoesNotThrow(() -> channelManager.unregisterAdapter("nonexistent"));
    }

    // ── Lifecycle ──

    @Test
    @DisplayName("startAll starts all registered adapters")
    void testStartAll() {
        ChannelAdapter slack = mockAdapter("slack", false);
        ChannelAdapter discord = mockAdapter("discord", false);
        channelManager.registerAdapter(slack);
        channelManager.registerAdapter(discord);

        channelManager.startAll();

        verify(slack).start();
        verify(discord).start();
    }

    @Test
    @DisplayName("stopAll stops all registered adapters")
    void testStopAll() {
        ChannelAdapter slack = mockAdapter("slack", true);
        ChannelAdapter discord = mockAdapter("discord", true);
        channelManager.registerAdapter(slack);
        channelManager.registerAdapter(discord);

        channelManager.stopAll();

        verify(slack).stop();
        verify(discord).stop();
    }

    @Test
    @DisplayName("startAll isolates errors between adapters")
    void testStartAllErrorIsolation() {
        ChannelAdapter failing = mockAdapter("failing", false);
        doThrow(RuntimeException.class).when(failing).start();

        ChannelAdapter working = mockAdapter("working", false);
        channelManager.registerAdapter(failing);
        channelManager.registerAdapter(working);

        assertDoesNotThrow(() -> channelManager.startAll());
        verify(working).start();
    }

    @Test
    @DisplayName("stopAll isolates errors between adapters")
    void testStopAllErrorIsolation() {
        ChannelAdapter failing = mockAdapter("failing", true);
        doThrow(RuntimeException.class).when(failing).stop();

        ChannelAdapter working = mockAdapter("working", true);
        channelManager.registerAdapter(failing);
        channelManager.registerAdapter(working);

        assertDoesNotThrow(() -> channelManager.stopAll());
        verify(working).stop();
    }

    @Test
    @DisplayName("startChannel starts specific adapter")
    void testStartChannel() {
        ChannelAdapter adapter = mockAdapter("slack", false);
        channelManager.registerAdapter(adapter);

        channelManager.startChannel("slack");

        verify(adapter).start();
    }

    @Test
    @DisplayName("stopChannel stops specific adapter")
    void testStopChannel() {
        ChannelAdapter adapter = mockAdapter("slack", true);
        channelManager.registerAdapter(adapter);

        channelManager.stopChannel("slack");

        verify(adapter).stop();
    }

    @Test
    @DisplayName("startChannel with unknown name does not throw")
    void testStartUnknownChannel() {
        assertDoesNotThrow(() -> channelManager.startChannel("unknown"));
    }

    // ── Queries ──

    @Test
    @DisplayName("getChannelNames returns all registered names")
    void testGetChannelNames() {
        channelManager.registerAdapter(mockAdapter("slack", false));
        channelManager.registerAdapter(mockAdapter("discord", false));

        List<String> names = channelManager.getChannelNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("slack"));
        assertTrue(names.contains("discord"));
    }

    @Test
    @DisplayName("getAllAdapters returns all adapters")
    void testGetAllAdapters() {
        channelManager.registerAdapter(mockAdapter("slack", false));
        channelManager.registerAdapter(mockAdapter("discord", false));

        List<ChannelAdapter> adapters = channelManager.getAllAdapters();

        assertEquals(2, adapters.size());
    }

    @Test
    @DisplayName("getStatus returns status for all adapters")
    void testGetStatus() {
        channelManager.registerAdapter(mockAdapter("slack", true));
        channelManager.registerAdapter(mockAdapter("discord", false));

        List<ChannelStatus> statuses = channelManager.getStatus();

        assertEquals(2, statuses.size());

        ChannelStatus slackStatus = statuses.stream()
                .filter(s -> s.channelName().equals("slack")).findFirst().orElseThrow();
        assertTrue(slackStatus.running());

        ChannelStatus discordStatus = statuses.stream()
                .filter(s -> s.channelName().equals("discord")).findFirst().orElseThrow();
        assertFalse(discordStatus.running());
    }

    @Test
    @DisplayName("Empty manager returns empty lists")
    void testEmptyManager() {
        assertTrue(channelManager.getChannelNames().isEmpty());
        assertTrue(channelManager.getAllAdapters().isEmpty());
        assertTrue(channelManager.getStatus().isEmpty());
    }

    // ── Replacement ──

    @Test
    @DisplayName("Registering adapter with same name replaces previous")
    void testAdapterReplacement() {
        ChannelAdapter first = mockAdapter("slack", false);
        ChannelAdapter second = mockAdapter("slack", false);

        channelManager.registerAdapter(first);
        channelManager.registerAdapter(second);

        Optional<ChannelAdapter> result = channelManager.getAdapter("slack");
        assertTrue(result.isPresent());
        assertSame(second, result.get());
    }
}
