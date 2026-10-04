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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads test-milestone JSON files in either of the two layouts on disk, as one format.
 *
 * <p>The {@code test_milestone} tool writes one file per run, named by a random id
 * ({@link #newRunId()}), with {@code totalTests} and {@code commitShort}. Test sweeps write one
 * file per test class, named by the class, with {@code total} and no {@code commitShort}.
 * {@link #normalize(Map)} gives both the tool's field names, so every reader sees the same keys,
 * and {@link #isRunId(String)} tells a run from a class result.</p>
 */
public final class TestMilestoneReader {

    /** The store, under a project directory, or under the home directory for the user's own. */
    public static final String STORE_DIR = ".kompile/test-milestones";
    public static final String MILESTONES_DIR = "milestones";
    public static final String CONFIG_FILE = "config.json";

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{8}");
    private static final int SHORT_COMMIT = 7;

    private TestMilestoneReader() {
    }

    /** The milestones in a directory, normalized, and the files that could not be parsed. */
    public record Contents(List<Map<String, Object>> milestones, List<Path> unreadable) {
    }

    /** The milestone store of a project, or of the user when given the home directory. */
    public static Path storeDir(Path base) {
        return base.resolve(STORE_DIR);
    }

    /** Every readable milestone in {@code dir}, normalized; unreadable files are skipped. */
    public static List<Map<String, Object>> readAll(Path dir) throws IOException {
        return readDir(dir).milestones();
    }

    /** Every milestone in {@code dir}; a malformed file is listed, and does not hide the rest. */
    public static Contents readDir(Path dir) throws IOException {
        List<Map<String, Object>> milestones = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return new Contents(milestones, unreadable);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    milestones.add(readFile(file));
                } catch (Exception e) {
                    unreadable.add(file);
                }
            }
        }
        return new Contents(milestones, unreadable);
    }

    /** A new run id: the first eight hex digits of a random UUID. */
    public static String newRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** True for an id the {@code test_milestone} tool gives a run; sweeps name a result by its test class. */
    public static boolean isRunId(String id) {
        return id != null && RUN_ID.matcher(id).matches();
    }

    /**
     * The milestone with {@code id}, normalized, or null when there is none.
     *
     * @throws IllegalArgumentException when the id could name a file outside {@code dir}
     * @throws IOException              when the file exists but cannot be read or parsed
     */
    public static Map<String, Object> read(Path dir, String id) throws IOException {
        Path file = fileFor(dir, id);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return readFile(file);
    }

    /**
     * One milestone file, normalized.
     *
     * @throws IOException when the file cannot be read or parsed, or holds JSON {@code null}
     */
    public static Map<String, Object> readFile(Path file) throws IOException {
        Map<String, Object> milestone = MAPPER.readValue(file.toFile(), MAP_TYPE);
        if (milestone == null) {
            throw new IOException(file + " does not hold a JSON object");
        }
        return normalize(milestone);
    }

    /**
     * The file that holds milestone {@code id} in {@code dir}.
     *
     * @throws IllegalArgumentException when the id is blank or could name a file outside {@code dir}
     */
    public static Path fileFor(Path dir, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A milestone id is required");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.indexOf(':') >= 0
                || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid milestone id '" + id + "': ids are file names");
        }
        Path file = dir.resolve(id + ".json");
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.equals(dir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Invalid milestone id '" + id + "': ids are file names");
        }
        return file;
    }

    /** The {@code config.json} beside the milestones directory, or null when absent or unreadable. */
    public static Map<String, Object> readConfig(Path baseDir) {
        Path file = baseDir == null ? null : baseDir.resolve(CONFIG_FILE);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The milestone with the {@code test_milestone} tool's field names: {@code total} becomes
     * {@code totalTests} and a missing {@code commitShort} follows {@code commit}. Key order is
     * kept; a milestone that already has both is returned as is.
     */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        boolean renameTotal = raw.containsKey("total") && !raw.containsKey("totalTests");
        boolean addShort = raw.get("commit") instanceof String && !raw.containsKey("commitShort");
        if (!renameTotal && !addShort) {
            return raw;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (renameTotal && "total".equals(key)) {
                result.put("totalTests", value);
                return;
            }
            result.put(key, value);
            if (addShort && "commit".equals(key)) {
                result.put("commitShort", shortCommit((String) value));
            }
        });
        return result;
    }

    /**
     * The count a milestone records under {@code key}: a JSON number, or a string holding one, as the
     * insight sources read it. Null when there is none, or the value is something else, such as a note
     * in a hand-written file.
     */
    public static Integer count(Map<String, Object> milestone, String key) {
        Long value = milestone == null ? null : Values.number(milestone.get(key));
        return value == null ? null : value.intValue();
    }

    /** The first seven characters of a commit hash; anything else (such as "uncommitted") unchanged. */
    public static String shortCommit(String commit) {
        if (commit == null) {
            return null;
        }
        return commit.length() > SHORT_COMMIT && HEX.matcher(commit).matches()
                ? commit.substring(0, SHORT_COMMIT) : commit;
    }
}
