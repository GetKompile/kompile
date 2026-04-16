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

package ai.kompile.notebook.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Auto-configuration for the kompile-notebook module.
 *
 * This configuration is loaded via Spring Boot's auto-configuration mechanism
 * (see META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
 *
 * JPA repository scanning and entity scanning are handled by PrimaryDataSourceConfig
 * in kompile-app-main (ai.kompile.notebook.repository and ai.kompile.notebook.domain
 * are added to the explicit package lists there).
 *
 * Enables:
 * - Component scan for services, controllers, and tool beans
 * - @Async for NoteEmbeddingService.scheduleEmbedding()
 */
@Configuration
@EnableAsync
@ComponentScan(basePackages = {
    "ai.kompile.notebook.service",
    "ai.kompile.notebook.controller",
    "ai.kompile.notebook.tools"
})
public class NotebookAutoConfiguration {
    // All beans are discovered via @ComponentScan.
    // JPA repos + entity scan are registered in PrimaryDataSourceConfig.
}
