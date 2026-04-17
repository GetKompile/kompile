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

package ai.kompile.app.facts;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot application used only in @DataJpaTest tests under
 * ai.kompile.app.facts. Scoped to this package so Spring's configuration search
 * resolves here instead of walking up to {@code ai.kompile.app.MainApplication},
 * which pulls in the full application graph.
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "ai.kompile.app.facts.repository")
@EntityScan(basePackages = "ai.kompile.app.facts.domain")
public class FactsTestApplication {
}
