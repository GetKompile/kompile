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

/**
 * Shared Spring property expressions for staging configuration.
 */
public final class StagingPropertyKeys {
    public static final String MODELS_DIR_VALUE =
            "${kompile.staging.models-dir:${kompile.staging.model-dir:#{systemProperties['user.home'] + '/.kompile/models'}}}";

    private StagingPropertyKeys() {
    }
}
