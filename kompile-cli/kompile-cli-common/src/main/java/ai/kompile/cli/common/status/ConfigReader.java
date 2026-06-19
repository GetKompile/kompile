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

package ai.kompile.cli.common.status;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads JSON configuration files from {@code ~/.kompile/config/}.
 */
public class ConfigReader {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /**
     * Reads all {@code *.json} files from the config directory.
     *
     * @return map keyed by filename (sans {@code .json}) to parsed content
     */
    public static Map<String, Object> readAll() {
        Map<String, Object> configs = new LinkedHashMap<>();
        File dir = KompileHome.configDirectory();
        if (!dir.exists() || !dir.isDirectory()) {
            return configs;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return configs;
        }
        for (File file : files) {
            String key = file.getName().replace(".json", "");
            Object value = readOne(file);
            if (value != null) {
                configs.put(key, value);
            }
        }
        return configs;
    }

    /**
     * Parses a single JSON file, returning null on error.
     */
    public static Object readOne(File file) {
        try {
            return MAPPER.readValue(file, Object.class);
        } catch (Exception e) {
            return Map.of("_error", "Failed to parse: " + e.getMessage());
        }
    }
}
