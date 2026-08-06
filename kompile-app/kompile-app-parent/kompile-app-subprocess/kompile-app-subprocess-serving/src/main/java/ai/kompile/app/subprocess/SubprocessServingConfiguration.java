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

package ai.kompile.app.subprocess;

import ai.kompile.app.llm.pipeline.LlmGenerateController;
import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Minimal bean graph for the LLM serving subprocess.
 *
 * <p>This is a whitelist configuration: it imports only the controllers and model
 * implementation used by {@link ServingSubprocessHttpServer}. The subprocess uses
 * an {@code AnnotationConfigApplicationContext} plus the JDK HTTP server, matching
 * the established graph/pipeline native-subprocess pattern. It deliberately does
 * not start a second Spring Boot application inside the parent native image.</p>
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kompile.llm.direct-serving.enabled", havingValue = "true", matchIfMissing = false)
@Import({
        LlmGenerateController.class,
        LlmModelController.class,
        SameDiffLanguageModelImpl.class
})
public class SubprocessServingConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonUtils.newStandardMapper();
    }

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder();
    }

    @Bean
    public Profiler profiler() {
        return NoOpProfiler.INSTANCE;
    }

    @Bean
    public Metrics metrics() {
        return NoOpMetrics.INSTANCE;
    }
}
