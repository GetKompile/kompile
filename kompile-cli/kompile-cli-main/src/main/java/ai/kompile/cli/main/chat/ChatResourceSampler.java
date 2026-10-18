package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.grounding.LocalEmbeddingRuntime;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only OS telemetry: no model loads, backend initialization, or watchdog enforcement. */
final class ChatResourceSampler {
    private record CpuSample(long time, long cpu, Instant started) { }
    record Metrics(double cpu, long rssBytes, long gpuMb) { }
    record GpuSnapshot(String devices, Map<Long, Long> processMemory, boolean available) { }
    private final Path root;
    private final String sessionId;
    private final BackgroundProcessManager processes;
    private final Map<Long, CpuSample> cpuSamples = new HashMap<>();
    private GpuSnapshot gpu = new GpuSnapshot("GPU telemetry unavailable (NVIDIA probe not sampled)", Map.of(), false);
    private long lastGpuSample;
    private java.util.concurrent.CompletableFuture<Map<String, Object>> embeddingStatus;
    private Map<String, Object> lastEmbeddingStatus = Map.of("state", "telemetry pending (may be loading)");
    record ChildTelemetry(long pid, String kind, Map<String, Object> snapshot) { }
    private java.util.concurrent.CompletableFuture<List<ChildTelemetry>> servingStatus;
    private List<ChildTelemetry> lastServingStatus = List.of();
    private long lastServingPoll;
    private static final com.fasterxml.jackson.databind.ObjectMapper TELEMETRY_MAPPER =
            ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final java.net.http.HttpClient TELEMETRY_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(1)).build();

    ChatResourceSampler(Path root, String sessionId, BackgroundProcessManager processes) {
        this.root = new ai.kompile.project.KompileProjectStore().findProjectRoot(root)
                .orElse(root.toAbsolutePath().normalize());
        this.sessionId = sessionId;
        this.processes = processes;
    }

    synchronized String sample() {
        long now = System.nanoTime();
        if (lastGpuSample == 0 || now - lastGpuSample > TimeUnit.SECONDS.toNanos(gpu.available() ? 2 : 15)) {
            gpu = sampleGpu();
            lastGpuSample = now;
        }
        updateChildTelemetry(now);
        StringBuilder out = new StringBuilder("Updated ").append(Instant.now()).append("\n");
        out.append(host()).append('\n').append(deviceMemorySummary()).append('\n')
                .append("Optional NVIDIA OS supplement:\n").append(gpu.devices()).append("\n\n");
        Map<Long, List<SessionEntry>> hosts = new LinkedHashMap<>();
        for (SessionEntry entry : SessionRegistry.load().getAll()) {
            if ("running".equals(entry.getStatus()) && entry.isProcessAlive())
                hosts.computeIfAbsent(entry.getPid(), ignored -> new ArrayList<>()).add(entry);
        }
        long ownPid = ProcessHandle.current().pid();
        if (hosts.getOrDefault(ownPid, List.of()).stream().noneMatch(e -> sessionId.equals(e.getKompileSessionId()))) {
            SessionEntry own = new SessionEntry();
            own.setKompileSessionId(sessionId);
            own.setTitle("current chat");
            hosts.computeIfAbsent(ownPid, ignored -> new ArrayList<>()).add(own);
        }
        Map<Long, Metrics> readings = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        out.append("CHATS (process tree; CPU 100% = one logical core)\n");
        for (var host : hosts.entrySet()) {
            List<ProcessHandle> tree = processTree(host.getKey(), hosts.keySet());
            List<Metrics> members = new ArrayList<>();
            for (ProcessHandle handle : tree) {
                members.add(readings.computeIfAbsent(handle.pid(), ignored -> read(handle, now)));
                seen.add(handle.pid());
            }
            Metrics total = total(members);
            out.append("PID ").append(host.getKey()).append(" · ").append(format(total));
            if (host.getValue().size() > 1) out.append(" · SHARED JVM: not individually attributable");
            out.append('\n');
            for (SessionEntry entry : host.getValue()) {
                out.append("  ").append(sessionId.equals(entry.getKompileSessionId()) ? "* " : "  ")
                        .append(text(entry.getKompileSessionId())).append(" · ").append(text(entry.getTitle()))
                        .append(" · ").append(text(entry.getAgent())).append('\n');
            }
        }
        out.append("\nTHIS CHAT'S REGISTERED PROCESSES (per-PID; not extra totals)\n");
        for (var entry : processes.listRunning()) {
            out.append(entry.getId()).append(" · ").append(entry.getKind().label()).append(" · PID ")
                    .append(entry.getPid()).append(" · ").append(metric(entry.getPid(), now, readings, seen))
                    .append(" · ").append(text(entry.getDescription())).append('\n');
        }
        out.append("\nLOCAL JVM'S MANAGED SUBPROCESSES (may be shared by chats/crawls)\n");
        for (var child : LocalSubprocessWatchdog.get().list()) {
            out.append("watchdog:").append(child.id()).append(" · ").append(text(child.type()))
                    .append(" · PID ").append(child.pid()).append(" · ")
                    .append(metric(child.pid(), now, readings, seen)).append(" · ").append(text(child.description())).append('\n');
        }
        Map<String, Object> embedding = lastEmbeddingStatus;
        Map<String, Object> lifecycle = new LinkedHashMap<>(embedding);
        lifecycle.remove("deviceMemory"); // Render memory once, with units and provenance, above.
        out.append("Folder embeddings: ").append(text(lifecycle.toString())).append('\n');
        Object pid = embedding.get("pid");
        if (pid instanceof Number number && number.longValue() > 0)
            out.append("  ").append(metric(number.longValue(), now, readings, seen)).append('\n');
        cpuSamples.keySet().retainAll(seen);
        out.append("\nRSS includes native/off-heap memory; JVM heap is only part of RSS.\n")
                .append("Device API memory is backend-scoped; tracked allocations are NOT physical VRAM.\n")
                .append("NVIDIA OS readings are optional: utilization is device-wide; VRAM supplement covers compute-apps only, not all Vulkan/graphics allocations.\n")
                .append("Remote MCP-host children are not local JVM children; manage those on their owner via subprocess_watchdog.\n")
                .append(ResourceMonitorCommands.HELP);
        return out.toString();
    }

    private void updateChildTelemetry(long now) {
        // Loading can hold the embedding lifecycle monitor for minutes; the UI never waits for it.
        if (embeddingStatus != null && embeddingStatus.isDone()) {
            try { lastEmbeddingStatus = embeddingStatus.join(); }
            catch (RuntimeException failure) { lastEmbeddingStatus = Map.of("state", "telemetry unavailable"); }
            embeddingStatus = null;
        }
        if (embeddingStatus == null) embeddingStatus = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> LocalEmbeddingRuntime.runtimeStatus(root));
        if (servingStatus != null && servingStatus.isDone()) {
            try { lastServingStatus = servingStatus.join(); }
            catch (RuntimeException failure) { lastServingStatus = List.of(); }
            servingStatus = null;
        }
        if (servingStatus == null && (lastServingPoll == 0 || now - lastServingPoll > TimeUnit.SECONDS.toNanos(2))) {
            lastServingPoll = now;
            // Inventory is passive: no lease, launch, model load, or keep-warm extension.
            var residents = LocalServingRuntimePool.residentRuntimes();
            servingStatus = java.util.concurrent.CompletableFuture.supplyAsync(() -> fetchServingTelemetry(residents));
        }
    }

    static List<ChildTelemetry> fetchServingTelemetry(List<KompileLocalServingBootstrap.StartupResult> residents) {
        List<ChildTelemetry> result = new ArrayList<>();
        if (residents.isEmpty()) return result;
        for (var runtime : residents) {
            Process child = runtime.process();
            if (child == null || !child.isAlive()) continue;
            Map<String, Object> snapshot = Map.of();
            try {
                var request = java.net.http.HttpRequest.newBuilder(runtime.baseUrl().resolve("/api/llm/status"))
                        .timeout(java.time.Duration.ofSeconds(1)).GET().build();
                var response = TELEMETRY_HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().length() <= 65536) {
                    var value = TELEMETRY_MAPPER.readTree(response.body()).path("deviceMemory");
                    if (value.isObject()) snapshot = TELEMETRY_MAPPER.convertValue(value,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); break;
            } catch (Exception unavailable) { /* Old child/offline endpoint remains explicitly unavailable. */ }
            result.add(new ChildTelemetry(child.pid(), "serving", snapshot));
        }
        return result;
    }

    private String deviceMemorySummary() {
        long timestamp = System.currentTimeMillis();
        StringBuilder summary = new StringBuilder("DEVICE MEMORY — managed child device API (backend-agnostic)\n");
        for (ChildTelemetry child : lastServingStatus) {
            if (ProcessHandle.of(child.pid()).map(ProcessHandle::isAlive).orElse(false))
                summary.append("  serving ").append(renderDeviceMemory(child.pid(), child.snapshot(), timestamp)).append('\n');
        }
        Object embeddingMemory = lastEmbeddingStatus.get("deviceMemory");
        if (embeddingMemory instanceof Map<?, ?> map && map.get("pid") instanceof Number pid
                && ProcessHandle.of(pid.longValue()).map(ProcessHandle::isAlive).orElse(false)) {
            summary.append("  embeddings ").append(renderDeviceMemory(pid.longValue(), map, timestamp)).append('\n');
        } else summary.append("  embeddings: no fresh child device-memory report\n");
        if (lastServingStatus.isEmpty()) summary.append("  serving: no resident child report (monitoring starts nothing)\n");
        return summary.toString();
    }

    static String renderDeviceMemory(long expectedPid, Map<?, ?> report, long nowMillis) {
        String prefix = "PID " + expectedPid + ": ";
        if (!(report.get("pid") instanceof Number pid) || pid.longValue() != expectedPid
                || !(report.get("sampledAt") instanceof Number timestamp)
                || timestamp.longValue() > nowMillis + 2000 || nowMillis - timestamp.longValue() > 15000
                || !"device-api".equals(report.get("source")) || !Integer.valueOf(1).equals(report.get("schemaVersion")))
            return prefix + "device memory N/A (missing, stale or mismatched report)";
        if (!(report.get("devices") instanceof List<?> devices) || devices.isEmpty())
            return prefix + "device memory " + text(String.valueOf(report.get("state")));
        List<String> rows = new ArrayList<>();
        for (Object value : devices) {
            if (!(value instanceof Map<?, ?> device)) continue;
            rows.add(text(String.valueOf(device.get("backendId"))) + "/" + text(String.valueOf(device.get("deviceId")))
                    + " [" + text(String.valueOf(device.get("deviceType"))) + "] " + text(String.valueOf(device.get("name")))
                    + "\n      total " + memoryReading(device.get("totalBytes"))
                    + " · available/budget " + memoryReading(device.get("availableBytes"))
                    + "\n      framework tracked " + memoryReading(device.get("trackedAllocatedBytes")));
        }
        return prefix + "sampled " + Instant.ofEpochMilli(((Number) report.get("sampledAt")).longValue())
                + "\n    " + String.join("\n    ", rows);
    }

    private static String memoryReading(Object value) {
        return value instanceof Number number && number.longValue() >= 0 ? bytes(number.longValue()) : "N/A";
    }

    private String metric(long pid, long now, Map<Long, Metrics> readings, Set<Long> seen) {
        Optional<ProcessHandle> handle = ProcessHandle.of(Math.max(0, pid));
        if (handle.isEmpty() || !handle.get().isAlive()) return "not running / no OS PID";
        seen.add(pid);
        return format(readings.computeIfAbsent(pid, ignored -> read(handle.get(), now)));
    }

    private Metrics read(ProcessHandle handle, long now) {
        var info = handle.info();
        long cpu = info.totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);
        Instant started = info.startInstant().orElse(null);
        CpuSample previous = cpuSamples.put(handle.pid(), new CpuSample(now, cpu, started));
        double percent = previous == null || !Objects.equals(started, previous.started())
                ? Double.NaN : cpuPercent(previous.cpu(), cpu, now - previous.time());
        long rss = -1;
        try { rss = parseRss(Files.readString(Path.of("/proc", Long.toString(handle.pid()), "status"))); }
        catch (Exception ignored) { /* RSS unavailable on non-procfs hosts. */ }
        long vram = gpu.available() ? gpu.processMemory().getOrDefault(handle.pid(), 0L) : -1L;
        return new Metrics(percent, rss, vram);
    }

    static double cpuPercent(long before, long after, long elapsed) {
        return before < 0 || after < before || elapsed <= 0 ? Double.NaN : (after - before) * 100.0 / elapsed;
    }

    static long parseRss(String status) {
        for (String line : status.split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length == 3 && fields[0].equals("VmRSS:") && fields[2].equals("kB")) {
                try { long kb = Long.parseLong(fields[1]); return kb < 0 ? -1 : Math.multiplyExact(kb, 1024); }
                catch (NumberFormatException | ArithmeticException ignored) { return -1; }
            }
        }
        return -1;
    }

    static Metrics total(List<Metrics> rows) {
        double cpu = 0; long rss = 0, vram = 0;
        for (Metrics row : rows) {
            cpu = Double.isNaN(cpu) || Double.isNaN(row.cpu()) ? Double.NaN : cpu + row.cpu();
            rss = rss < 0 || row.rssBytes() < 0 ? -1 : rss + row.rssBytes();
            vram = vram < 0 || row.gpuMb() < 0 ? -1 : vram + row.gpuMb();
        }
        return rows.isEmpty() ? new Metrics(Double.NaN, -1, -1) : new Metrics(cpu, rss, vram);
    }

    static List<ProcessHandle> processTree(long pid, Set<Long> otherChatRoots) {
        List<ProcessHandle> result = new ArrayList<>();
        Deque<ProcessHandle> pending = new ArrayDeque<>();
        ProcessHandle.of(pid).ifPresent(pending::add);
        Set<Long> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            ProcessHandle next = pending.removeFirst();
            if (!visited.add(next.pid()) || !next.isAlive()) continue;
            result.add(next);
            try (var children = next.children()) {
                children.filter(child -> !otherChatRoots.contains(child.pid())).forEach(pending::addLast);
            }
        }
        return result;
    }

    private static String format(Metrics row) {
        return "CPU " + percent(row.cpu()) + " · RSS " + bytes(row.rssBytes())
                + " · NVIDIA compute VRAM supplement " + (row.gpuMb() < 0 ? "N/A" : row.gpuMb() + " MiB");
    }
    private static String percent(double value) {
        return !Double.isFinite(value) || value < 0 ? "N/A" : String.format(Locale.ROOT, "%.1f%%", value);
    }
    private static String bytes(long value) {
        return value < 0 ? "N/A" : String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }
    private static String text(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ");
    }

    private static String host() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize(), available = os.getFreeMemorySize();
            try {
                Map<String, Long> memory = new HashMap<>();
                for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                    String[] fields = line.strip().split("\\s+");
                    if (fields.length == 3 && fields[2].equals("kB")) memory.put(fields[0], Long.parseLong(fields[1]) * 1024);
                }
                if (memory.containsKey("MemTotal:") && memory.containsKey("MemAvailable:")) {
                    total = memory.get("MemTotal:"); available = memory.get("MemAvailable:");
                }
            } catch (Exception ignored) { /* MXBean fallback uses a consistent numerator/denominator. */ }
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            return "HOST CPU " + percent(os.getCpuLoad() * 100) + " · RAM " + bytes(total - available)
                    + " / " + bytes(total) + " · available " + bytes(available)
                    + "\nCURRENT JVM heap " + bytes(heap.getUsed()) + " / " + bytes(heap.getMax())
                    + " · threads " + ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Exception ignored) { return "Host CPU/RAM/JVM telemetry unavailable"; }
    }

    private static GpuSnapshot sampleGpu() {
        String devices = query("--query-gpu=uuid,name,memory.used,memory.total,utilization.gpu");
        String applications = query("--query-compute-apps=pid,used_gpu_memory");
        if (devices == null || devices.isBlank())
            return new GpuSnapshot("GPU telemetry unavailable (non-NVIDIA, missing driver or probe timed out)", Map.of(), false);
        StringBuilder summary = new StringBuilder("GPUs (device-wide):\n");
        for (String line : devices.split("\\R")) {
            String[] fields = line.split(",");
            if (fields.length == 5) summary.append("  ").append(text(fields[0].strip())).append(" · ")
                    .append(text(fields[1].strip())).append(" · VRAM ").append(text(fields[2].strip()))
                    .append(" / ").append(text(fields[3].strip())).append(" MiB · utilization ")
                    .append(text(fields[4].strip())).append("%\n");
        }
        return new GpuSnapshot(summary.toString(), applications == null ? Map.of() : parseGpuProcesses(applications), applications != null);
    }

    static Map<Long, Long> parseGpuProcesses(String csv) {
        Map<Long, Long> result = new HashMap<>();
        for (String line : csv.split("\\R")) {
            String[] fields = line.split(",");
            if (fields.length != 2) continue;
            try {
                long pid = Long.parseLong(fields[0].strip());
                long mb;
                try { mb = Long.parseLong(fields[1].strip()); }
                catch (NumberFormatException unavailable) { result.put(pid, -1L); continue; }
                if (pid > 0) result.merge(pid, mb, (a, b) -> a < 0 || b < 0 ? -1L : a + b);
            } catch (NumberFormatException ignored) { /* malformed row, never attributed to another PID */ }
        }
        return Map.copyOf(result);
    }

    /** Bounded subprocess query: wait BEFORE reading; never hang the terminal on a stalled driver. */
    private static String query(String argument) {
        Process process = null;
        try {
            process = new ProcessBuilder("nvidia-smi", argument, "--format=csv,noheader,nounits")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            return new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception ignored) { return null; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
