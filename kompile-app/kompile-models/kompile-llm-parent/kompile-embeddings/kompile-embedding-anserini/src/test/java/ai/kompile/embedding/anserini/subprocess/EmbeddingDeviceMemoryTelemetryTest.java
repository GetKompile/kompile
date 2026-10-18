package ai.kompile.embedding.anserini.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingDeviceMemoryTelemetryTest {
    @Test void extendedHeartbeatRoundTripsBackendIdentityAndOldHeartbeatRemainsReadable() throws Exception {
        var mapper = JsonUtils.standardMapper();
        Map<String, Object> memory = Map.of("schemaVersion", 1, "source", "device-api", "pid", 42,
                "sampledAt", 1000, "devices", List.of(Map.of("backendId", "vulkan", "deviceId", "vulkan:gpu:0")));
        var heartbeat = new EmbeddingSubprocessMessage.Heartbeat(1, true, "encoder", 2, 3, 4, 5, 6, memory);
        var parsed = (EmbeddingSubprocessMessage.Heartbeat) mapper.readValue(mapper.writeValueAsString(heartbeat),
                EmbeddingSubprocessMessage.class);
        assertEquals(memory, parsed.deviceMemory());
        var old = mapper.valueToTree(new EmbeddingSubprocessMessage.Heartbeat(1, true, "encoder", 2, 3, 4, 5, 6));
        ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove("deviceMemory");
        var compatible = (EmbeddingSubprocessMessage.Heartbeat) mapper.treeToValue(old, EmbeddingSubprocessMessage.class);
        assertNull(compatible.deviceMemory());
        assertEquals(5, compatible.totalEmbeddings());
    }

    @Test void launcherCachesOnlyCurrentLiveChildTelemetryWithoutLaunchingOrRequestingWork() {
        var launcher = EmbeddingSubprocessLauncher.builder().build();
        Process child = mock(Process.class);
        when(child.pid()).thenReturn(42L);
        when(child.isAlive()).thenReturn(true);
        launcher.process = child;
        var memory = Map.<String, Object>of("schemaVersion", 1, "source", "device-api", "pid", 42L,
                "sampledAt", 1000L, "devices", List.of());
        var heartbeat = new EmbeddingSubprocessMessage.Heartbeat(1, true, "encoder", 2, 3, 4, 5, 6, memory);
        ReflectionTestUtils.invokeMethod(launcher, "handleMessage", heartbeat);
        assertEquals(memory, launcher.getDeviceMemoryStatus());
        when(child.pid()).thenReturn(43L);
        assertTrue(launcher.getDeviceMemoryStatus().isEmpty(), "replacement PID must not inherit old GPU allocation");
        when(child.pid()).thenReturn(42L);
        when(child.isAlive()).thenReturn(false);
        assertTrue(launcher.getDeviceMemoryStatus().isEmpty());
        verify(child, never()).getOutputStream();
        launcher.process = null; // Mock was never launched; no process cleanup side effects.
    }
}
