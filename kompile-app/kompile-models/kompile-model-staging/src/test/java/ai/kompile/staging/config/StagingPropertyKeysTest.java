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

package ai.kompile.staging.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StagingPropertyKeysTest {

    @Test
    void modelsDirValueFallsBackToLegacyModelDir() {
        String resolved = resolve(Map.of(
                "kompile.staging.model-dir", "/tmp/legacy-models"));

        assertEquals("/tmp/legacy-models", resolved);
    }

    @Test
    void modelsDirValuePrefersCanonicalModelsDir() {
        String resolved = resolve(Map.of(
                "kompile.staging.models-dir", "/tmp/canonical-models",
                "kompile.staging.model-dir", "/tmp/legacy-models"));

        assertEquals("/tmp/canonical-models", resolved);
    }

    private String resolve(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment.resolvePlaceholders(StagingPropertyKeys.MODELS_DIR_VALUE);
    }
}
