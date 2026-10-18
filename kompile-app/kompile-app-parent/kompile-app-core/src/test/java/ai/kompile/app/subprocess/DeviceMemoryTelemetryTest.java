package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeviceMemoryTelemetryTest {
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> devices(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("devices");
    }

    @Test void overlappingDeviceIndicesRemainBackendScopedAndOwnershipIsNotInferred() {
        var snapshot = DeviceMemoryTelemetry.collect(42, 1000, () -> List.of(
                new DeviceMemoryTelemetry.Probe("vulkan", "vulkan:gpu:0", "VULKAN", 0, "AMD Vulkan",
                        () -> 1000, () -> 600, () -> 10),
                new DeviceMemoryTelemetry.Probe("rocm", "rocm:gpu:0", "HIP", 0, "AMD HIP",
                        () -> 2000, () -> 800, () -> 20)));
        assertEquals("device-api", snapshot.get("source"));
        assertEquals(42L, snapshot.get("pid"));
        assertEquals("available", snapshot.get("state"));
        var rows = devices(snapshot);
        assertEquals(2, rows.size());
        assertEquals("vulkan:gpu:0", rows.get(0).get("deviceId"));
        assertEquals("rocm:gpu:0", rows.get(1).get("deviceId"));
        assertEquals(10L, rows.get(0).get("trackedAllocatedBytes"));
        assertNotEquals(400L, rows.get(0).get("trackedAllocatedBytes"));
        assertTrue(rows.get(0).get("scope").toString().contains("NOT physical"));
    }

    @Test void unsupportedCapacityAndTrackingAreNullNotFabricatedZero() {
        AtomicInteger freeCalls = new AtomicInteger();
        var row = devices(DeviceMemoryTelemetry.collect(42, 1000, () -> List.of(
                new DeviceMemoryTelemetry.Probe("backend", "backend:gpu:0", "GPU", 0, "unknown",
                        () -> 0, () -> { freeCalls.incrementAndGet(); return 123; }, () -> -1)))).get(0);
        assertNull(row.get("totalBytes"));
        assertNull(row.get("availableBytes"));
        assertNull(row.get("trackedAllocatedBytes"));
        assertEquals(0, freeCalls.get());
    }

    @Test void individualProbeFailuresDoNotHideOtherDevicesOrRealZeroAllocations() {
        var rows = devices(DeviceMemoryTelemetry.collect(42, 1000, () -> List.of(
                new DeviceMemoryTelemetry.Probe("vulkan", "vk:0", "VULKAN", 0, "first",
                        () -> { throw new UnsupportedOperationException(); }, () -> 0, () -> 0),
                new DeviceMemoryTelemetry.Probe("hip", "hip:0", "HIP", 0, "second",
                        () -> 100, () -> 200, () -> 5))));
        assertEquals(2, rows.size());
        assertNull(rows.get(0).get("totalBytes"));
        assertEquals(0L, rows.get(0).get("trackedAllocatedBytes"));
        assertNull(rows.get(1).get("availableBytes"), "inconsistent free/total is unknown");
        assertEquals(5L, rows.get(1).get("trackedAllocatedBytes"));
    }

    @Test void enumerationFailureIsUnavailableAndCpuOnlyIsNotGpuZero() {
        var unavailable = DeviceMemoryTelemetry.collect(42, 1000,
                () -> { throw new UnsatisfiedLinkError("missing backend"); });
        assertEquals("unavailable", unavailable.get("state"));
        assertTrue(devices(unavailable).isEmpty());
        assertEquals("no-accelerators", DeviceMemoryTelemetry.collect(42, 1000, List::of).get("state"));
    }
}
