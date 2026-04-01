package ai.kompile.kvcache.service;

import ai.kompile.kvcache.model.StatsSample;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public class KVCacheStatisticsCollector {

    private final int windowSeconds;
    private final ConcurrentHashMap<String, CacheCounters> countersByCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StatsSample>> samplesByCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Supplier<Long>> memorySuppliers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Supplier<Integer>> sequenceSuppliers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> samplingTask;

    public KVCacheStatisticsCollector(int windowSeconds) {
        this.windowSeconds = windowSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kvcache-stats-sampler");
            t.setDaemon(true);
            return t;
        });
        this.samplingTask = scheduler.scheduleAtFixedRate(this::collectSamples, 1, 1, TimeUnit.SECONDS);
    }

    public void registerCache(String name, Supplier<Long> memorySupplier, Supplier<Integer> sequenceSupplier) {
        countersByCache.put(name, new CacheCounters());
        samplesByCache.put(name, new CopyOnWriteArrayList<>());
        memorySuppliers.put(name, memorySupplier);
        sequenceSuppliers.put(name, sequenceSupplier);
    }

    public void unregisterCache(String name) {
        countersByCache.remove(name);
        samplesByCache.remove(name);
        memorySuppliers.remove(name);
        sequenceSuppliers.remove(name);
    }

    public void recordAppend(String cacheName) {
        CacheCounters c = countersByCache.get(cacheName);
        if (c != null) c.appends.incrementAndGet();
    }

    public void recordEviction(String cacheName) {
        CacheCounters c = countersByCache.get(cacheName);
        if (c != null) c.evictions.incrementAndGet();
    }

    public void recordFree(String cacheName) {
        CacheCounters c = countersByCache.get(cacheName);
        if (c != null) c.frees.incrementAndGet();
    }

    public void recordPrefixHit(String cacheName) {
        CacheCounters c = countersByCache.get(cacheName);
        if (c != null) c.hits.incrementAndGet();
    }

    public void recordPrefixMiss(String cacheName) {
        CacheCounters c = countersByCache.get(cacheName);
        if (c != null) c.misses.incrementAndGet();
    }

    public CacheCounters getCounters(String cacheName) {
        return countersByCache.get(cacheName);
    }

    public List<StatsSample> getTimeSeries(String cacheName, int windowSecs) {
        CopyOnWriteArrayList<StatsSample> samples = samplesByCache.get(cacheName);
        if (samples == null) return Collections.emptyList();

        long cutoff = System.currentTimeMillis() - (windowSecs * 1000L);
        List<StatsSample> result = new ArrayList<>();
        for (StatsSample s : samples) {
            if (s.getTimestamp() >= cutoff) {
                result.add(s);
            }
        }
        return result;
    }

    private void collectSamples() {
        long now = System.currentTimeMillis();
        long cutoff = now - (windowSeconds * 1000L);

        for (String cacheName : countersByCache.keySet()) {
            try {
                CacheCounters c = countersByCache.get(cacheName);
                if (c == null) continue;

                long memBytes = 0;
                int seqs = 0;
                Supplier<Long> memSup = memorySuppliers.get(cacheName);
                Supplier<Integer> seqSup = sequenceSuppliers.get(cacheName);
                if (memSup != null) memBytes = memSup.get();
                if (seqSup != null) seqs = seqSup.get();

                CopyOnWriteArrayList<StatsSample> samples = samplesByCache.get(cacheName);
                if (samples == null) continue;

                // Compute rates from last sample
                double appendsPerSec = 0;
                double evictionsPerSec = 0;
                if (!samples.isEmpty()) {
                    StatsSample last = samples.get(samples.size() - 1);
                    long dt = now - last.getTimestamp();
                    if (dt > 0) {
                        // Use counter deltas (approximation based on current totals)
                        appendsPerSec = c.appendsSinceLastSample.getAndSet(0) * 1000.0 / dt;
                        evictionsPerSec = c.evictionsSinceLastSample.getAndSet(0) * 1000.0 / dt;
                    }
                }

                samples.add(StatsSample.builder()
                        .timestamp(now)
                        .memoryUsedBytes(memBytes)
                        .activeSequences(seqs)
                        .appendsPerSecond(appendsPerSec)
                        .evictionsPerSecond(evictionsPerSec)
                        .build());

                // Trim old samples
                samples.removeIf(s -> s.getTimestamp() < cutoff);
            } catch (Exception e) {
                log.trace("Error collecting stats for cache {}: {}", cacheName, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        samplingTask.cancel(false);
        scheduler.shutdownNow();
    }

    public static class CacheCounters {
        public final AtomicLong appends = new AtomicLong();
        public final AtomicLong evictions = new AtomicLong();
        public final AtomicLong frees = new AtomicLong();
        public final AtomicLong hits = new AtomicLong();
        public final AtomicLong misses = new AtomicLong();
        final AtomicLong appendsSinceLastSample = new AtomicLong();
        final AtomicLong evictionsSinceLastSample = new AtomicLong();
    }
}
