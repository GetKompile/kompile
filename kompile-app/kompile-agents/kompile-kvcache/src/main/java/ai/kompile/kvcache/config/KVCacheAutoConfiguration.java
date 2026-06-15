package ai.kompile.kvcache.config;

import ai.kompile.kvcache.bridge.VlmKvCacheIntegrationService;
import ai.kompile.kvcache.service.KVCacheCheckpointService;
import ai.kompile.kvcache.service.KVCacheConfigPersistence;
import ai.kompile.kvcache.service.KVCacheManager;
import ai.kompile.kvcache.service.KVCachePrefixService;
import ai.kompile.kvcache.service.KVCacheStatisticsCollector;
import ai.kompile.kvcache.service.PriorityEvictionPolicy;
import ai.kompile.kvcache.service.ContentHashPrefixIndex;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Always loads KV cache beans so the UI can configure and enable/disable at runtime.
 * The KVCacheManager checks properties.isEnabled() internally before performing operations.
 * Config is persisted to ~/.kompile/kvcache/config.json for survival across restarts.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KVCacheProperties.class)
public class KVCacheAutoConfiguration {

    @Bean
    public KVCacheConfigPersistence kvCacheConfigPersistence(KVCacheProperties properties) {
        KVCacheConfigPersistence persistence = new KVCacheConfigPersistence();
        // Load persisted config on startup (overrides application.properties defaults)
        persistence.loadInto(properties);
        return persistence;
    }

    @Bean
    public KVCacheStatisticsCollector kvCacheStatisticsCollector(KVCacheProperties properties) {
        return new KVCacheStatisticsCollector(properties.getStatsWindowSeconds());
    }

    @Bean
    public PriorityEvictionPolicy priorityEvictionPolicy(KVCacheProperties properties) {
        return new PriorityEvictionPolicy(properties.getDefaultBlockPriority());
    }

    @Bean
    public ContentHashPrefixIndex contentHashPrefixIndex(KVCacheProperties properties) {
        return new ContentHashPrefixIndex(properties.getBlockSize(), properties.getPrefixHashMaxEntries());
    }

    @Bean
    public KVCacheManager kvCacheManager(KVCacheProperties properties,
                                         KVCacheStatisticsCollector statisticsCollector,
                                         PriorityEvictionPolicy priorityEvictionPolicy,
                                         ContentHashPrefixIndex contentHashPrefixIndex) {
        return new KVCacheManager(properties, statisticsCollector, priorityEvictionPolicy, contentHashPrefixIndex);
    }

    @Bean
    public KVCacheCheckpointService kvCacheCheckpointService(KVCacheProperties properties,
                                                              KVCacheManager cacheManager) {
        return new KVCacheCheckpointService(properties, cacheManager);
    }

    @Bean
    public KVCachePrefixService kvCachePrefixService(KVCacheProperties properties) {
        return new KVCachePrefixService(properties);
    }

    @Bean
    public VlmKvCacheIntegrationService vlmKvCacheIntegrationService(KVCacheProperties properties,
                                                                      KVCacheManager cacheManager,
                                                                      KVCacheStatisticsCollector statisticsCollector) {
        return new VlmKvCacheIntegrationService(properties, cacheManager, statisticsCollector);
    }
}
