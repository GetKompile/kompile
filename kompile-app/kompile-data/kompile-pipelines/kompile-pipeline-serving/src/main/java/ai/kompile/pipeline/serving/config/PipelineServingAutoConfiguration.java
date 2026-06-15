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

package ai.kompile.pipeline.serving.config;

import ai.kompile.pipeline.serving.launcher.PipelineSubprocessLauncher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for pipeline serving infrastructure.
 * Registers the subprocess launcher as a Spring bean.
 */
@AutoConfiguration
public class PipelineServingAutoConfiguration {

    @Bean
    public PipelineSubprocessLauncher pipelineSubprocessLauncher() {
        return new PipelineSubprocessLauncher();
    }
}
