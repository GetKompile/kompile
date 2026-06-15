/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.config;

import ai.kompile.modelmanager.cache.FileBasedPipelineOutputCache;
import ai.kompile.modelmanager.cache.PipelineOutputCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring configuration for the pipeline output cache.
 *
 * <p>Creates and initializes a {@link FileBasedPipelineOutputCache} bean
 * that provides content-hash-based caching for pipeline outputs.</p>
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li>{@code kompile.cache.pipeline.enabled} - Enable/disable caching (default: true)</li>
 *   <li>{@code kompile.cache.pipeline.directory} - Cache directory (default: ~/.kompile/cache/pipeline-outputs)</li>
 *   <li>{@code kompile.cache.pipeline.ttl-days} - Time-to-live in days (default: 30)</li>
 *   <li>{@code kompile.cache.pipeline.max-size-gb} - Max cache size in GB (default: 10)</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class PipelineCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineCacheConfig.class);

    @Value("${kompile.cache.pipeline.enabled:true}")
    private boolean cacheEnabled;

    @Value("${kompile.cache.pipeline.directory:#{null}}")
    private String cacheDirectory;

    @Value("${kompile.cache.pipeline.ttl-days:30}")
    private int ttlDays;

    @Value("${kompile.cache.pipeline.max-size-gb:10}")
    private int maxSizeGb;

    private FileBasedPipelineOutputCache cacheInstance;

    @Bean
    public PipelineOutputCache pipelineOutputCache() {
        if (!cacheEnabled) {
            log.info("Pipeline output cache is disabled");
            return new ai.kompile.modelmanager.cache.NoOpPipelineOutputCache();
        }

        Path cacheDir;
        if (cacheDirectory != null && !cacheDirectory.isEmpty()) {
            cacheDir = Paths.get(cacheDirectory);
        } else {
            cacheDir = Paths.get(System.getProperty("user.home"), ".kompile", "cache", "pipeline-outputs");
        }

        long maxSizeBytes = (long) maxSizeGb * 1024 * 1024 * 1024;

        cacheInstance = new FileBasedPipelineOutputCache(cacheDir, ttlDays, maxSizeBytes);
        cacheInstance.init();

        log.info("Pipeline output cache initialized: dir={}, ttl={}d, maxSize={}GB",
                cacheDir, ttlDays, maxSizeGb);

        return cacheInstance;
    }

    @PreDestroy
    public void shutdown() {
        if (cacheInstance != null) {
            cacheInstance.shutdown();
        }
    }
}
