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

package ai.kompile.app.process;

import ai.kompile.process.discovery.mining.ActivityEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * Provides the mining module's infra-free {@link ActivityEmbedder} SPI without running
 * embeddings in the main application JVM.
 *
 * <p>The previous implementation batch-embedded labels directly from app-main. That can initialize SameDiff/ND4J in the main process during process mining, bypassing
 * the subprocess queue, watchdog, and adaptive batching used by crawl embeddings. Until activity
 * label embedding has a dedicated subprocess RPC, return an empty map so mining remains
 * structural-only instead of doing hidden main-process embedding.</p>
 */
@Configuration
public class MiningActivityEmbedderConfig {

    @Bean
    @Primary
    public ActivityEmbedder miningActivityEmbedder() {
        return labels -> {
            if (labels == null || labels.isEmpty()) {
                return Map.of();
            }
            return Map.of();
        };
    }
}
