package ai.kompile.app.subprocess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Wire snapshot for already-initialized child device APIs. Never interprets free memory as ownership. */
public final class DeviceMemoryTelemetry {
    private DeviceMemoryTelemetry() { }

    public record Probe(String backendId, String deviceId, String deviceType, int deviceIndex,
                        String name, LongSupplier total, LongSupplier available, LongSupplier tracked) { }

    public static Map<String, Object> collect(long pid, long sampledAt, Supplier<List<Probe>> source) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("source", "device-api");
        snapshot.put("pid", pid);
        snapshot.put("sampledAt", sampledAt);
        List<Map<String, Object>> devices = new ArrayList<>();
        snapshot.put("devices", devices);
        try {
            for (Probe probe : source.get()) {
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("backendId", probe.backendId());
                device.put("deviceId", probe.deviceId());
                device.put("deviceType", probe.deviceType());
                device.put("deviceIndex", probe.deviceIndex());
                device.put("name", probe.name());
                Long total = reading(probe.total());
                if (total != null && total == 0) total = null; // Device API reports 0 for missing capacity.
                Long available = total == null ? null : reading(probe.available());
                if (available != null && available > total) available = null;
                device.put("totalBytes", total);
                device.put("availableBytes", available);
                device.put("trackedAllocatedBytes", reading(probe.tracked()));
                device.put("scope", "backend capacity/budget; tracked allocations are NOT physical process VRAM");
                devices.add(device);
            }
            snapshot.put("state", devices.isEmpty() ? "no-accelerators" : "available");
        } catch (RuntimeException | LinkageError failure) {
            snapshot.put("state", "unavailable");
            snapshot.put("error", failure.getClass().getSimpleName());
        }
        return snapshot;
    }

    private static Long reading(LongSupplier query) {
        try { long value = query.getAsLong(); return value < 0 ? null : value; }
        catch (RuntimeException | LinkageError unsupported) { return null; }
    }
}
