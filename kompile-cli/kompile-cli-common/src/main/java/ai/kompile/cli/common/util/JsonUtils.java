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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Canonical {@link ObjectMapper} factory for the entire kompile codebase.
 *
 * <p>All code — Spring beans, static utility fields, CLI commands — should
 * obtain its {@code ObjectMapper} from this class rather than calling
 * {@code new ObjectMapper()} directly.  This guarantees consistent module
 * registration (JavaTimeModule), date serialization (ISO-8601), and
 * deserialization leniency across the whole project.</p>
 *
 * <h3>Which mapper to use</h3>
 * <ul>
 *   <li>{@link #standardMapper()} — the default choice. Registers
 *       {@link JavaTimeModule}, writes dates as ISO-8601 strings, and
 *       ignores unknown properties on deserialization.</li>
 *   <li>{@link #newStandardMapper()} — returns a <em>new</em> instance
 *       with the same configuration. Use this when you need to add extra
 *       modules or features without mutating the shared singleton (e.g.,
 *       inside a Spring {@code @Bean} method).</li>
 *   <li>{@link #lenientMapper()} — legacy alias, identical configuration
 *       to {@link #standardMapper()}.</li>
 * </ul>
 *
 * <p>{@link ObjectMapper} is thread-safe once configured, so sharing a
 * single instance across many callers is both correct and efficient.
 * <strong>Do not</strong> call {@code configure()}, {@code registerModule()},
 * etc. on a shared singleton — use {@link #newStandardMapper()} instead.</p>
 */
public final class JsonUtils {

    private static final ObjectMapper STANDARD_MAPPER = createStandardMapper();

    private JsonUtils() {}

    /**
     * Returns the shared, immutable {@link ObjectMapper} singleton.
     *
     * <p>Configured with:
     * <ul>
     *   <li>{@link JavaTimeModule} — correct Instant/LocalDateTime/etc. handling</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS WRITE_DATES_AS_TIMESTAMPS} = false — ISO-8601 strings</li>
     *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES FAIL_ON_UNKNOWN_PROPERTIES} = false — forward-compatible deserialization</li>
     * </ul>
     *
     * @return the shared standard mapper — do not mutate
     */
    public static ObjectMapper standardMapper() {
        return STANDARD_MAPPER;
    }

    /**
     * Creates a <em>new</em> {@link ObjectMapper} with the standard kompile
     * configuration. Use this when you need a mutable instance — e.g., inside
     * a Spring {@code @Bean} factory method where the container may apply
     * additional customizers.
     *
     * @return a fresh, independently-configurable mapper
     */
    public static ObjectMapper newStandardMapper() {
        return createStandardMapper();
    }

    /**
     * Legacy alias for {@link #standardMapper()}.
     *
     * @return the shared standard mapper — do not mutate
     */
    public static ObjectMapper lenientMapper() {
        return STANDARD_MAPPER;
    }

    private static ObjectMapper createStandardMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
