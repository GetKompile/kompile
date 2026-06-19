package ai.kompile.kvcache.service;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.PrefixCacheStats;
import ai.kompile.kvcache.model.PrefixEntry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.kvcache.PrefixLookupResult;
import org.eclipse.deeplearning4j.llm.generation.kvcache.RadixPrefixCache;

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
    private ContentHashPrefixIndex contentHashPrefixIndex;
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();

    public KVCachePrefixService(KVCacheProperties properties) {
        this(properties, null);
    }

    public KVCachePrefixService(KVCacheProperties properties, ContentHashPrefixIndex contentHashPrefixIndex) {
        this.properties = properties;
        this.contentHashPrefixIndex = contentHashPrefixIndex;
        int blockSize = properties.getBlockSize();
        int maxEntries = properties.getPrefixCacheMaxEntries();
        // Estimate bytes per block: 2 * numKvHeads * headDim * blockSize * 4
        long bytesPerBlock = 2L * properties.getNumKvHeads() * properties.getHeadDim() * blockSize * 4;
        this.prefixCache = new RadixPrefixCache(blockSize, maxEntries, bytesPerBlock);
        log.info("KVCachePrefixService initialized (maxEntries={}, blockSize={}, hashIndex={})",
                maxEntries, blockSize, contentHashPrefixIndex != null);
    }

    public void setContentHashPrefixIndex(ContentHashPrefixIndex index) {
        this.contentHashPrefixIndex = index;
    }

    public PrefixLookupResult lookup(int[] tokenIds) {
        totalLookups.incrementAndGet();

        // Check content-hash index first for O(1) prefix match
        if (contentHashPrefixIndex != null) {
            ContentHashPrefixIndex.PrefixMatchResult hashResult = contentHashPrefixIndex.findCachedPrefix(tokenIds);
            if (hashResult.hasMatch()) {
                totalHits.incrementAndGet();
                log.debug("Content-hash prefix match: {} tokens matched ({} blocks)",
                        hashResult.matchedTokens(), hashResult.matchedBlockIds().length);
                // Convert to PrefixLookupResult format
                return prefixCache.lookup(tokenIds); // Fall through to radix for full result
            }
        }

        PrefixLookupResult result = prefixCache.lookup(tokenIds);
        if (result.hasMatch()) {
            totalHits.incrementAndGet();
        }
        return result;
    }

    public void register(int[] tokenIds, int[] blockIds) {
        prefixCache.register(tokenIds, blockIds);

        // Also index block content hashes for cross-request matching
        if (contentHashPrefixIndex != null && tokenIds != null && blockIds != null) {
            int blockSize = properties.getBlockSize();
            for (int i = 0; i < blockIds.length && (i + 1) * blockSize <= tokenIds.length; i++) {
                int[] blockTokens = Arrays.copyOfRange(tokenIds, i * blockSize, (i + 1) * blockSize);
                contentHashPrefixIndex.onBlockFilled(blockIds[i], blockTokens);
            }
        }
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
