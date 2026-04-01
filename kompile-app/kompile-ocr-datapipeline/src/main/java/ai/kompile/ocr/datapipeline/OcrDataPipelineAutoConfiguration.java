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

package ai.kompile.ocr.datapipeline;

import ai.kompile.ocr.datapipeline.api.PipelineConfigStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-configuration for OCR Data Pipeline components.
 * Always enabled - runtime control via UI/API.
 */
@AutoConfiguration
@ComponentScan(basePackages = "ai.kompile.ocr.datapipeline")
public class OcrDataPipelineAutoConfiguration {

    private static final String DEFAULT_DATA_DIR = System.getProperty("user.home") + "/.kompile";

    @Bean
    @ConditionalOnMissingBean
    public PipelineConfigStore pipelineConfigStore() {
        Path configDir = Paths.get(DEFAULT_DATA_DIR, "config", "ocr-pipelines");
        return new PipelineConfigStore(configDir);
    }
}
