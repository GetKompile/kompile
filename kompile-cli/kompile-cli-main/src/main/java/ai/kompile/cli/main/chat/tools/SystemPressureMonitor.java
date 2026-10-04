package ai.kompile.cli.main.chat.tools;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Host pressure watchdog, deliberately restricted by its owner to live, owned command trees. */
public final class SystemPressureMonitor implements AutoCloseable {
    record Reading(String resource, double percent) { }
    private static final class Trend {
        double percent;
        double velocity;
        long time;
        int samples;
        int risingSamples;
        int breaches;
    }

    private final Supplier<SystemPressureConfig.Settings> config;
    private final Supplier<Map<String, Reading>> probe;
    private final Supplier<List<String>> targets;
    private final BiPredicate<String, String> kill;
    private final LongSupplier clock;
    private final Map<String, Trend> trends = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;
    private SystemPressureConfig.Settings previousConfig;
    private long lastPoll = Long.MIN_VALUE;
    private long lastKill = Long.MIN_VALUE;
    private long kills;
    private boolean closed;
    private Map<String, Object> status = Map.of("state", "not sampled");
    private Map<String, Object> lastEvent = Map.of();

    SystemPressureMonitor(Supplier<SystemPressureConfig.Settings> config,
                          Supplier<Map<String, Reading>> probe, Supplier<List<String>> targets,
                          BiPredicate<String, String> kill, LongSupplier clock) {
        this.config = config;
        this.probe = probe;
        this.targets = targets;
        this.kill = kill;
        this.clock = clock;
    }

    static SystemPressureMonitor create(Path root, Supplier<List<String>> targets,
                                        BiPredicate<String, String> kill) {
        return new SystemPressureMonitor(() -> {
            try { return SystemPressureConfig.settings(SystemPressureConfig.load(
                    SystemPressureConfig.userFile(), SystemPressureConfig.projectFile(root))); }
            catch (Exception failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        }, () -> sample(root), targets, kill, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    synchronized void start() {
        if (closed || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-system-pressure-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
    }

    /** Bounded and serialized: status calls never take extra samples or advance breach counts. */
    synchronized void check() {
        if (closed) return;
        try {
            SystemPressureConfig.Settings settings = config.get();
            long now = clock.getAsLong();
            if (!settings.equals(previousConfig)) {
                trends.clear();
                lastPoll = Long.MIN_VALUE;
                previousConfig = settings;
            }
            if (!settings.enabled()) {
                trends.clear();
                status = Map.of("state", "disabled", "config", settings.values());
                return;
            }
            List<String> candidates = targets.get();
            if (candidates.isEmpty()) {
                trends.clear();
                lastPoll = Long.MIN_VALUE;
                status = Map.of("state", "no owned commands", "config", settings.values());
                return;
            }
            if (lastPoll != Long.MIN_VALUE && now - lastPoll < settings.intervalMs()) return;
            // A delayed/missing probe cannot carry stale momentum into the next kill decision.
            if (lastPoll != Long.MIN_VALUE && now - lastPoll > settings.intervalMs() * 3) trends.clear();
            lastPoll = now;
            Map<String, Reading> readings = probe.get();
            trends.keySet().retainAll(readings.keySet());
            Map<String, Object> metrics = new LinkedHashMap<>();
            List<String> reasons = new ArrayList<>();
            for (var entry : readings.entrySet()) {
                Reading reading = entry.getValue();
                if (!Double.isFinite(reading.percent()) || reading.percent() < 0 || reading.percent() > 100) {
                    trends.remove(entry.getKey());
                    continue;
                }
                Trend trend = trends.computeIfAbsent(entry.getKey(), ignored -> new Trend());
                double delta = reading.percent() - trend.percent;
                if (trend.samples > 0 && now > trend.time) {
                    double slope = delta * 1000 / (now - trend.time);
                    trend.velocity = settings.momentumAlpha() * slope + (1 - settings.momentumAlpha()) * trend.velocity;
                    trend.risingSamples = slope >= settings.minMomentumPercentPerSecond() ? trend.risingSamples + 1 : 0;
                }
                trend.percent = reading.percent();
                trend.time = now;
                trend.samples++;
                double threshold = settings.thresholds().getOrDefault(reading.resource(), 0.0);
                double projected = Math.min(100, trend.percent + Math.max(0, trend.velocity) * settings.projectionSeconds());
                boolean absolute = threshold > 0 && trend.percent >= threshold;
                boolean momentum = threshold > 0 && settings.projectionSeconds() > 0 && trend.risingSamples >= 2
                        && trend.velocity >= settings.minMomentumPercentPerSecond() && projected >= threshold;
                trend.breaches = absolute || momentum ? trend.breaches + 1 : 0;
                metrics.put(entry.getKey(), Map.of("percent", trend.percent, "percentPerSecond", trend.velocity,
                        "projectedPercent", projected, "thresholdPercent", threshold,
                        "consecutiveBreaches", trend.breaches, "samples", trend.samples));
                if (trend.breaches >= settings.breachCount()) reasons.add(entry.getKey() + " "
                        + String.format(java.util.Locale.ROOT, "%.2f%% (%.2f pp/s, projected %.2f%%, limit %.2f%%; %s)",
                        trend.percent, trend.velocity, projected, threshold, absolute ? "threshold" : "momentum"));
            }
            status = Map.of("state", readings.isEmpty() ? "telemetry unavailable" : "monitoring", "config", settings.values(),
                    "metrics", metrics, "eligibleProcesses", List.copyOf(candidates));
            if (!reasons.isEmpty() && (lastKill == Long.MIN_VALUE || now - lastKill >= settings.cooldownMs())) {
                String reason = "System resource pressure: " + String.join("; ", reasons);
                // At most one newest eligible command per poll, then resample after cooldown.
                for (String id : candidates) {
                    boolean killed;
                    try {
                        killed = kill.test(id, reason);
                    } catch (RuntimeException failure) {
                        // A callback may throw after signalling a process. Do not assume no kill,
                        // retry a different target, or permit an immediate kill cascade.
                        lastKill = now;
                        trends.clear();
                        lastEvent = Map.of("processId", id, "reason", reason, "outcome", "unknown",
                                "error", String.valueOf(failure.getMessage()), "at", java.time.Instant.now().toString());
                        status = Map.of("state", "kill error (outcome unknown)", "config", settings.values(),
                                "metrics", metrics, "error", String.valueOf(failure.getMessage()));
                        return;
                    }
                    if (killed) {
                        kills++;
                        lastKill = now;
                        lastEvent = Map.of("processId", id, "reason", reason, "at", java.time.Instant.now().toString());
                        trends.clear();
                        break;
                    }
                }
            }
        } catch (RuntimeException failure) {
            trends.clear();
            status = Map.of("state", "monitor error (no kill)", "error", String.valueOf(failure.getMessage()));
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>(status);
        result.put("kills", kills);
        result.put("lastEvent", lastEvent);
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        trends.clear();
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Unknown metrics are omitted, never treated as 0% or as a reason to kill. No native backend initialization. */
    static Map<String, Reading> sample(Path root) {
        Map<String, Reading> result = new LinkedHashMap<>();
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            add(result, "cpu", "cpu", os.getCpuLoad() * 100);
            Path meminfo = Path.of("/proc/meminfo");
            if (Files.isReadable(meminfo)) {
                // Keep numerator and denominator in the same host snapshot. MXBean totals
                // may be container-limited, while /proc/meminfo describes the host.
                result.putAll(parseHostMemory(Files.readString(meminfo)));
            } else {
                ratio(result, "ram", "ram", os.getTotalMemorySize() - os.getFreeMemorySize(), os.getTotalMemorySize());
                ratio(result, "swap", "swap", os.getTotalSwapSpaceSize() - os.getFreeSwapSpaceSize(), os.getTotalSwapSpaceSize());
            }
        } catch (Exception ignored) { /* unsupported host telemetry */ }
        try {
            var store = Files.getFileStore(root);
            ratio(result, "disk", "disk", store.getTotalSpace() - store.getUsableSpace(), store.getTotalSpace());
        } catch (Exception ignored) { /* unsupported filesystem telemetry */ }
        Process process = null;
        try {
            process = new ProcessBuilder("nvidia-smi", "--query-gpu=uuid,memory.used,memory.total,utilization.gpu",
                    "--format=csv,noheader,nounits").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
                result.putAll(parseGpu(new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GPU sampling interrupted", interrupted);
        } catch (Exception ignored) { /* no NVIDIA telemetry; GPU metrics remain unavailable */ }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        return result;
    }

    static Map<String, Reading> parseHostMemory(String output) {
        Map<String, Long> fields = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 3 || !parts[2].equals("kB")) continue;
            if (!List.of("MemTotal:", "MemAvailable:", "SwapTotal:", "SwapFree:").contains(parts[0])) continue;
            try { fields.put(parts[0], Long.parseLong(parts[1])); }
            catch (NumberFormatException ignored) { /* unknown value, not a fabricated zero */ }
        }
        Map<String, Reading> result = new LinkedHashMap<>();
        for (String resource : List.of("ram", "swap")) {
            Long total = fields.get(resource.equals("ram") ? "MemTotal:" : "SwapTotal:");
            Long available = fields.get(resource.equals("ram") ? "MemAvailable:" : "SwapFree:");
            if (total != null && available != null && available >= 0 && available <= total)
                ratio(result, resource, resource, (double) total - available, total);
        }
        return result;
    }

    static Map<String, Reading> parseGpu(String output) {
        Map<String, Reading> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.split(",");
            if (fields.length != 4 || !fields[0].strip().startsWith("GPU-")) continue;
            String uuid = fields[0].strip();
            try { ratio(result, "gpuMemory:" + uuid, "gpuMemory", Double.parseDouble(fields[1].strip()),
                    Double.parseDouble(fields[2].strip())); } catch (NumberFormatException ignored) { }
            try { add(result, "gpuUtilization:" + uuid, "gpuUtilization", Double.parseDouble(fields[3].strip())); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private static void ratio(Map<String, Reading> result, String key, String resource, double used, double total) {
        if (Double.isFinite(total) && total > 0 && used >= 0 && used <= total) add(result, key, resource, used * 100 / total);
    }

    private static void add(Map<String, Reading> result, String key, String resource, double percent) {
        if (Double.isFinite(percent) && percent >= 0 && percent <= 100) result.put(key, new Reading(resource, percent));
    }
}
