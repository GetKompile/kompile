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

package ai.kompile.a2a.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the kompile A2A module.
 * Always loaded; runtime enable/disable is managed via
 * {@link A2AConfigService} and persisted to {@code ~/.kompile/config/a2a-config.json}.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "ai.kompile.a2a")
public class A2AAutoConfiguration {
}
