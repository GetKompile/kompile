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

import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the device routing configuration to the embedding model.
 * This lives in kompile-app-main because it has access to both
 * DeviceRoutingConfigService and AnseriniEmbeddingModelImpl.
 */
@Configuration(proxyBeanMethods = false)
public class DeviceRoutingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeviceRoutingAutoConfiguration.class);

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    private AnseriniEmbeddingModelImpl anseriniEmbeddingModel;

    @PostConstruct
    public void configureEmbeddingDeviceRouting() {
        if (deviceRoutingConfigService == null || anseriniEmbeddingModel == null) {
            return;
        }

        if (!deviceRoutingConfigService.isEnabled()) {
            log.debug("Device routing is disabled, skipping embedding configuration");
            return;
        }

        try {
            Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                    .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_EMBEDDING);
            anseriniEmbeddingModel.setDeviceRoutingOverrides(
                    routedConfig.maxThreads(),
                    routedConfig.maxMasterThreads(),
                    routedConfig.cudaCurrentDevice(),
                    routedConfig.maxDeviceMemory());
            log.info("Applied device routing to embedding model: maxThreads={}, cudaDevice={}",
                    routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
        } catch (Exception e) {
            log.warn("Failed to configure device routing for embedding: {}", e.getMessage());
        }
    }
}
