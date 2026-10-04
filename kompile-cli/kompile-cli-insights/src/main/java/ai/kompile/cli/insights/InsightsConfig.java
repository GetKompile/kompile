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

package ai.kompile.cli.insights;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/**
 * Limits and defaults for insight reports, read from {@code ~/.kompile/config/insights.json}.
 *
 * <p>Every key is optional; a missing key, or a limit that is not positive, keeps the default. The file is
 * resolved at call time, so a project data dir ({@code kompile.data.dir}) and a test-isolated
 * {@code user.home} both apply. A file that cannot be parsed keeps every default and is
 * reported through {@link #getWarning()} rather than printed, because the reports render
 * inside the chat TUI and over MCP stdio.</p>
 *
 * <p>The chat app's insights page edits the file through {@link #withSettings} and {@link #save}.</p>
 */
@Value
@Builder(toBuilder = true)
public class InsightsConfig {

    public static final String FILE_NAME = "insights.json";

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    /** Window used when the question names none. */
    @Builder.Default int defaultWindowDays = 7;
    /** Rows in a ranked table. */
    @Builder.Default int maxRows = 10;
    /** Recent examples listed under a table, such as the newest judge flags. */
    @Builder.Default int maxExamples = 5;
    /** Judge session logs read per report, most recently written first. */
    @Builder.Default int maxSessions = 200;
    /** Bytes read from the end of one judge session log. */
    @Builder.Default long maxBytesPerFile = 4L * 1024 * 1024;
    /** Bytes read from the end of the combined tool-call index (about a month of calls). */
    @Builder.Default long maxToolIndexBytes = 256L * 1024 * 1024;
    /** Points in a sparkline and in a chart series. */
    @Builder.Default int sparklineBuckets = 14;
    /**
     * Show this session's judge flags, tool calls, latest test result and crawls in the chat
     * dashboard area when the project configures no dashboard of its own.
     */
    @Builder.Default boolean sessionPanel = true;

    /** Why the file was ignored, or null when it loaded (or does not exist). */
    String warning;

    public static InsightsConfig defaults() {
        return InsightsConfig.builder().build();
    }

    public static Path configFile() {
        return KompileHome.configDirectory().toPath().resolve(FILE_NAME);
    }

    public static InsightsConfig load() {
        return load(configFile());
    }

    public static InsightsConfig load(Path file) {
        InsightsConfig defaults = defaults();
        if (file == null || !Files.isRegularFile(file)) {
            return defaults;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return defaults.toBuilder().warning(file + " is not a JSON object; using defaults").build();
            }
            return defaults.toBuilder()
                    .defaultWindowDays(positiveInt(root, "defaultWindowDays", defaults.defaultWindowDays))
                    .maxRows(positiveInt(root, "maxRows", defaults.maxRows))
                    .maxExamples(positiveInt(root, "maxExamples", defaults.maxExamples))
                    .maxSessions(positiveInt(root, "maxSessions", defaults.maxSessions))
                    .maxBytesPerFile(positiveLong(root, "maxBytesPerFile", defaults.maxBytesPerFile))
                    .maxToolIndexBytes(positiveLong(root, "maxToolIndexBytes", defaults.maxToolIndexBytes))
                    .sparklineBuckets(positiveInt(root, "sparklineBuckets", defaults.sparklineBuckets))
                    .sessionPanel(root.path("sessionPanel").asBoolean(defaults.sessionPanel))
                    .build();
        } catch (Exception e) {
            return defaults.toBuilder()
                    .warning("Could not read " + file + " (" + Format.clamp(String.valueOf(e.getMessage()), 200)
                            + "); using defaults")
                    .build();
        }
    }

    /** The settings as the file holds them, without the warning: what {@link #save} writes. */
    public ObjectNode toJson() {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("defaultWindowDays", defaultWindowDays);
        json.put("maxRows", maxRows);
        json.put("maxExamples", maxExamples);
        json.put("maxSessions", maxSessions);
        json.put("maxBytesPerFile", maxBytesPerFile);
        json.put("maxToolIndexBytes", maxToolIndexBytes);
        json.put("sparklineBuckets", sparklineBuckets);
        json.put("sessionPanel", sessionPanel);
        return json;
    }

    /**
     * This config with the settings {@code changes} names, and no warning. {@link #load} keeps the
     * default for a value it cannot use, so that a hand-edited file never breaks a report; a change
     * made here must be valid instead: one of the file's keys, holding a positive whole number, or
     * true or false for {@code sessionPanel}.
     *
     * @throws IllegalArgumentException naming the first key that is unknown or holds an invalid value
     */
    public InsightsConfig withSettings(JsonNode changes) {
        if (changes == null || !changes.isObject()) {
            throw new IllegalArgumentException("Insights settings must be a JSON object");
        }
        InsightsConfigBuilder builder = toBuilder().warning(null);
        Iterator<String> keys = changes.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode value = changes.get(key);
            switch (key) {
                case "defaultWindowDays" -> builder.defaultWindowDays(requirePositiveInt(key, value));
                case "maxRows" -> builder.maxRows(requirePositiveInt(key, value));
                case "maxExamples" -> builder.maxExamples(requirePositiveInt(key, value));
                case "maxSessions" -> builder.maxSessions(requirePositiveInt(key, value));
                case "maxBytesPerFile" -> builder.maxBytesPerFile(requirePositiveLong(key, value));
                case "maxToolIndexBytes" -> builder.maxToolIndexBytes(requirePositiveLong(key, value));
                case "sparklineBuckets" -> builder.sparklineBuckets(requirePositiveInt(key, value));
                case "sessionPanel" -> {
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException("sessionPanel must be true or false");
                    }
                    builder.sessionPanel(value.booleanValue());
                }
                default -> throw new IllegalArgumentException("Unknown insights setting '"
                        + Format.clamp(key, 64) + "'");
            }
        }
        return builder.build();
    }

    /**
     * Writes {@link #toJson()} to {@code file}, creating its directory. The file is replaced in one
     * step, so a report reading it meanwhile sees the old settings or the new ones.
     */
    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), FILE_NAME + "-", ".tmp");
        try {
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * {@code text} followed by {@link #getWarning() the warning}, unless the text already says it: a
     * topic report lists the warning among its notes, while the overview carries only headlines.
     */
    public String appendWarning(String text) {
        String body = text == null ? "" : text;
        if (warning == null || body.contains(warning)) {
            return body;
        }
        if (body.isEmpty()) {
            return warning + "\n";
        }
        return (body.endsWith("\n") ? body : body + "\n") + "\n" + warning + "\n";
    }

    private static int positiveInt(JsonNode root, String key, int fallback) {
        int value = root.path(key).asInt(fallback);
        return value > 0 ? value : fallback;
    }

    private static long positiveLong(JsonNode root, String key, long fallback) {
        long value = root.path(key).asLong(fallback);
        return value > 0 ? value : fallback;
    }

    private static int requirePositiveInt(String key, JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException(key + " must be a positive whole number");
        }
        return value.intValue();
    }

    private static long requirePositiveLong(String key, JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(key + " must be a positive whole number");
        }
        return value.longValue();
    }
}
