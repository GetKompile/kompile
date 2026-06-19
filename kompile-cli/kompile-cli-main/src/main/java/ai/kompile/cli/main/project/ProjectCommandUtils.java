/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectComponentType;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectStorageBackend;
import ai.kompile.project.KompileProjectStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Package-private static utility methods shared across the project command classes.
 */
final class ProjectCommandUtils {

    private ProjectCommandUtils() {
        // utility class
    }

    static Path resolveProjectRoot(KompileProjectStore store, File root) {
        Path candidate = root.toPath().toAbsolutePath().normalize();
        return store.findProjectRoot(candidate).orElse(candidate);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static boolean hasTag(List<String> tags, String expectedTag) {
        if (tags == null) {
            return false;
        }
        return tags.stream().anyMatch(tag -> expectedTag.equalsIgnoreCase(tag));
    }

    static String normalizeEnum(String value) {
        return firstNonBlank(value, "local")
                .trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    static String defaultDirectoryName(String remoteUrl) {
        String cleaned = remoteUrl;
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        return cleaned.isBlank() ? "kompile-project" : cleaned;
    }

    static KompileProjectStorageBackend parseBackend(String value) {
        return KompileProjectStorageBackend.valueOf(normalizeEnum(value));
    }

    static KompileProjectComponentType parseType(String value) {
        return KompileProjectComponentType.valueOf(normalizeEnum(value));
    }

    static KompileProjectLifecycleState parseLifecycle(String value) {
        return KompileProjectLifecycleState.valueOf(normalizeEnum(value));
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    static String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String value : values == null ? List.<String>of() : values) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(jsonString(value));
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }
}
