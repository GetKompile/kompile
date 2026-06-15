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

package ai.kompile.app.config;

/**
 * Shared server URL constants for the Kompile platform.
 *
 * <p>These constants define the default base URLs for internal services so that
 * all modules reference the same value rather than independently hard-coding strings.
 * Override these defaults at runtime via Spring properties or JSON config where
 * supported by the consuming service.</p>
 */
public final class KompileServerConstants {

    private KompileServerConstants() {}

    /**
     * Default base URL for the kompile model-staging service.
     * The staging server hosts loaded LLM/embedding models and exposes an
     * OpenAI-compatible HTTP API on port 8090.
     */
    public static final String DEFAULT_STAGING_URL = "http://localhost:8090";

    /**
     * Default base URL for the kompile main application server (app-main).
     * Used as a fallback callback URL when the actual server port cannot be
     * determined from the running context.
     */
    public static final String DEFAULT_APP_URL = "http://localhost:8080";
}
