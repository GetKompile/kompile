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

import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.LlmObservabilityService;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.framework.core.data.JDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring Boot configuration for the LLM serving subprocess.
 *
 * <p>This is a whitelist configuration: it imports only the small servlet/Jackson
 * set needed for the LLM REST endpoints plus the LLM beans themselves. It does
 * not enable broad Spring Boot auto-configuration, so optional app modules on
 * the parent classpath cannot activate inside the serving subprocess.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kompile.llm.direct-serving.enabled", havingValue = "true", matchIfMissing = false)
@ImportAutoConfiguration({
        ServletWebServerFactoryAutoConfiguration.class,
        EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class
})
@Import({
        LlmModelController.class,
        LlmObservabilityService.class,
        SameDiffLanguageModelImpl.class
})
public class SubprocessServingConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    @Bean
    public DataFactory dataFactory() {
        return new JDataFactory();
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
