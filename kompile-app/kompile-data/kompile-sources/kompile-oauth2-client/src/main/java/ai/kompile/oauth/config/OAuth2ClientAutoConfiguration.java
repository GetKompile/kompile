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

package ai.kompile.oauth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for the OAuth2 client module.
 *
 * Note: JPA repository and entity scanning is handled by the main application's
 * PrimaryDataSourceConfig to avoid duplicate bean registration. Applications using
 * this module must include ai.kompile.oauth.repository in their @EnableJpaRepositories
 * and ai.kompile.oauth.domain in their @EntityScan.
 *
 * This auto-configuration is disabled in subprocess mode because:
 * - OAuthConnectionService requires OAuthConnectionRepository (JPA)
 * - Subprocesses exclude JPA to stay lightweight
 * - OAuth is not needed for document ingestion
 */
@AutoConfiguration
@ComponentScan(basePackages = "ai.kompile.oauth")
@EnableScheduling
@ConditionalOnClass(name = "ai.kompile.oauth.config.OAuth2ClientAutoConfiguration")
@ConditionalOnProperty(name = "kompile.subprocess.mode", havingValue = "false", matchIfMissing = true)
public class OAuth2ClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate oauthRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper oauthObjectMapper() {
        return new ObjectMapper();
    }
}
