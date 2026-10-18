package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ChatResourceSamplerTest {
    @Test void rssIsResidentNotVirtualAndMissingIsUnknown() {
        assertEquals(123 * 1024L, ChatResourceSampler.parseRss("VmSize: 9999 kB\nVmRSS:\t123 kB\n"));
        for (String input : new String[]{"VmSize: 42 kB", "VmRSS: bad kB", "VmRSS: -1 kB", "VmRSS: 42 bytes"})
            assertEquals(-1L, ChatResourceSampler.parseRss(input), input);
    }
    @Test void cpuUsesIntervalDeltaAndCanExceedOneCore() {
        assertEquals(200.0, ChatResourceSampler.cpuPercent(100, 300, 100));
        assertTrue(Double.isNaN(ChatResourceSampler.cpuPercent(-1, 300, 100)));
        assertTrue(Double.isNaN(ChatResourceSampler.cpuPercent(300, 100, 100)));
        assertTrue(Double.isNaN(ChatResourceSampler.cpuPercent(100, 300, 0)));
    }
    @Test void gpuMemorySumsAcrossDevicesAndKeepsUnsupportedUnknown() {
        var memory = ChatResourceSampler.parseGpuProcesses("123, 64\n123, 128\n456, N/A\n789, 0\nbad,row\n456, 50\n");
        assertEquals(192L, memory.get(123L));
        assertEquals(-1L, memory.get(456L));
        assertEquals(0L, memory.get(789L));
        assertEquals(3, memory.size());
        assertTrue(ChatResourceSampler.parseGpuProcesses("").isEmpty());
    }
    @Test void totalsDoNotPretendUnknownIsZero() {
        var sum = ChatResourceSampler.total(List.of(new ChatResourceSampler.Metrics(20, 100, 10),
                new ChatResourceSampler.Metrics(30, 200, 20)));
        assertEquals(new ChatResourceSampler.Metrics(50, 300, 30), sum);
        var unknown = ChatResourceSampler.total(List.of(sum, new ChatResourceSampler.Metrics(Double.NaN, -1, -1)));
        assertTrue(Double.isNaN(unknown.cpu()));
        assertEquals(-1, unknown.rssBytes());
        assertEquals(-1, unknown.gpuMb());
    }
    @Test void servingTelemetryReadsExistingChildStatusOnlyAndSkipsDeadOrAbsentRuntimes() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/llm/status", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"loaded\":true,\"deviceMemory\":{\"schemaVersion\":1,\"source\":\"device-api\",\"pid\":42,\"sampledAt\":1000,\"devices\":[]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        Process child = org.mockito.Mockito.mock(Process.class);
        org.mockito.Mockito.when(child.pid()).thenReturn(42L);
        org.mockito.Mockito.when(child.isAlive()).thenReturn(true);
        var runtime = new KompileLocalServingBootstrap.StartupResult("model", java.nio.file.Path.of("model"), null,
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, child, null, null);
        try {
            var rows = ChatResourceSampler.fetchServingTelemetry(List.of(runtime));
            assertEquals(1, rows.size());
            assertEquals(42, rows.get(0).pid());
            assertEquals("device-api", rows.get(0).snapshot().get("source"));
            assertEquals(1, calls.get());
            assertTrue(ChatResourceSampler.fetchServingTelemetry(List.of()).isEmpty());
            assertEquals(1, calls.get());
            org.mockito.Mockito.when(child.isAlive()).thenReturn(false);
            assertTrue(ChatResourceSampler.fetchServingTelemetry(List.of(runtime)).isEmpty());
            assertEquals(1, calls.get());
            org.mockito.Mockito.verify(child, org.mockito.Mockito.never()).getOutputStream();
        } finally { server.stop(0); }
    }

    @Test void backendScopedChildReportsRenderWithoutNvidiaAndWithoutOwnershipInference() {
        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1); report.put("source", "device-api");
        report.put("pid", 42L); report.put("sampledAt", 1000L);
        report.put("devices", List.of(
                java.util.Map.of("backendId", "vulkan", "deviceId", "vulkan:gpu:0", "deviceType", "VULKAN",
                        "name", "AMD Vulkan", "totalBytes", 104857600L, "availableBytes", 73400320L,
                        "trackedAllocatedBytes", 1048576L),
                java.util.Map.of("backendId", "rocm", "deviceId", "rocm:gpu:0", "deviceType", "HIP",
                        "name", "AMD HIP", "totalBytes", 209715200L, "availableBytes", 104857600L,
                        "trackedAllocatedBytes", 2097152L)));
        String text = ChatResourceSampler.renderDeviceMemory(42, report, 2000);
        assertTrue(text.contains("vulkan:gpu:0"), text);
        assertTrue(text.contains("rocm:gpu:0"), text);
        assertTrue(text.contains("total 100.0 MiB"), text);
        assertTrue(text.contains("available/budget 70.0 MiB"), text);
        assertTrue(text.contains("framework tracked 1.0 MiB"), text);
        for (String line : text.split("\\R")) {
            if (line.contains("total ") || line.contains("framework tracked "))
                assertTrue(line.length() < 79, "Memory values must remain visible in an 80-column managed passthrough: " + line);
        }
        assertFalse(text.contains("framework tracked 30.0 MiB"), "total-free is not child allocation");
        assertTrue(ChatResourceSampler.renderDeviceMemory(43, report, 2000).contains("N/A"));
        assertTrue(ChatResourceSampler.renderDeviceMemory(42, report, 17000).contains("N/A"));
        assertTrue(ChatResourceSampler.renderDeviceMemory(42, report, -2000).contains("N/A"));
        report.put("source", "nvidia-smi");
        assertTrue(ChatResourceSampler.renderDeviceMemory(42, report, 2000).contains("N/A"));
    }

    @Test void missingDeviceFieldsAreUnknownNotZero() {
        var report = java.util.Map.of("schemaVersion", 1, "source", "device-api", "pid", 42L, "sampledAt", 1000L,
                "devices", List.of(java.util.Map.of("backendId", "vulkan", "deviceId", "vk:0", "name", "unknown")));
        String text = ChatResourceSampler.renderDeviceMemory(42, report, 2000);
        assertTrue(text.contains("total N/A"));
        assertTrue(text.contains("available/budget N/A"));
        assertTrue(text.contains("framework tracked N/A"));
    }

    @Test void processTreeIncludesRootExactlyOnceEvenIfRegisteredAsChatRoot() {
        long pid = ProcessHandle.current().pid();
        var tree = ChatResourceSampler.processTree(pid, Set.of(pid));
        assertEquals(1, tree.stream().filter(p -> p.pid() == pid).count());
        assertEquals(tree.size(), tree.stream().map(ProcessHandle::pid).distinct().count());
    }
}
