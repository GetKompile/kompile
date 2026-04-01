package ai.kompile.kvcache.service;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.PrefixCacheStats;
import ai.kompile.kvcache.model.PrefixEntry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.PrefixLookupResult;
import org.eclipse.deeplearning4j.llm.generation.RadixPrefixCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class KVCachePrefixService {

    private final KVCacheProperties properties;
    private RadixPrefixCache prefixCache;
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();

    public KVCachePrefixService(KVCacheProperties properties) {
        this.properties = properties;
        int blockSize = properties.getBlockSize();
        int maxEntries = properties.getPrefixCacheMaxEntries();
        // Estimate bytes per block: 2 * numKvHeads * headDim * blockSize * 4
        long bytesPerBlock = 2L * properties.getNumKvHeads() * properties.getHeadDim() * blockSize * 4;
        this.prefixCache = new RadixPrefixCache(blockSize, maxEntries, bytesPerBlock);
        log.info("KVCachePrefixService initialized (maxEntries={}, blockSize={})", maxEntries, blockSize);
    }

    public PrefixLookupResult lookup(int[] tokenIds) {
        totalLookups.incrementAndGet();
        PrefixLookupResult result = prefixCache.lookup(tokenIds);
        if (result.hasMatch()) {
            totalHits.incrementAndGet();
        }
        return result;
    }

    public void register(int[] tokenIds, int[] blockIds) {
        prefixCache.register(tokenIds, blockIds);
    }

    public PrefixCacheStats getStats() {
        long lookups = totalLookups.get();
        long hits = totalHits.get();
        double hitRate = lookups > 0 ? (double) hits / lookups : 0.0;

        return PrefixCacheStats.builder()
                .totalEntries(prefixCache.size())
                .maxEntries(properties.getPrefixCacheMaxEntries())
                .totalLookups(lookups)
                .totalHits(hits)
                .hitRate(hitRate)
                .build();
    }

    public List<PrefixEntry> listEntries(int limit) {
        // RadixPrefixCache doesn't expose entry iteration directly
        // Return stats-based summary
        List<PrefixEntry> entries = new ArrayList<>();
        entries.add(PrefixEntry.builder()
                .prefixHash("summary")
                .tokenCount(0)
                .blockCount(prefixCache.size())
                .accessCount(totalLookups.get())
                .lastAccessed(System.currentTimeMillis())
                .build());
        return entries;
    }

    public void saveToDisk() throws IOException {
        Path dir = Paths.get(properties.getDiskOffloadPath());
        Files.createDirectories(dir);
        Path file = dir.resolve("prefix-cache.bin");
        prefixCache.saveToDisk(file);
        log.info("Prefix cache saved to disk: {}", file);
    }

    public void loadFromDisk() throws IOException {
        Path file = Paths.get(properties.getDiskOffloadPath(), "prefix-cache.bin");
        if (Files.exists(file)) {
            prefixCache = RadixPrefixCache.loadFromDisk(file);
            log.info("Prefix cache loaded from disk: {}", file);
        } else {
            log.warn("Prefix cache file not found: {}", file);
        }
    }
}
