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

package ai.kompile.cli.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared JSON utilities for kompile-cli-common and its dependents.
 *
 * <p>Provides a shared lenient {@link ObjectMapper} singleton that silently
 * ignores unknown JSON properties. Using a shared instance avoids the overhead
 * of constructing and configuring an {@code ObjectMapper} in every class that
 * needs basic JSON I/O.</p>
 *
 * <p>{@link ObjectMapper} is thread-safe once configured, so sharing a single
 * instance across many callers is both correct and efficient.</p>
 */
public final class JsonUtils {

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {}

    /**
     * Returns a shared {@link ObjectMapper} configured to ignore unknown JSON
     * properties. The returned instance must not be mutated by callers — only
     * use it for reading and writing; do not call {@code configure()},
     * {@code registerModule()}, etc. on the returned reference.
     *
     * @return the shared lenient mapper
     */
    public static ObjectMapper lenientMapper() {
        return LENIENT_MAPPER;
    }
}
