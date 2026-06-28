/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads live model limits from OpenCode's model registry output.
 *
 * <p>OpenCode exposes model metadata through {@code opencode models <provider> --verbose}.
 * The resume compactor uses these limits when available because provider-specific model
 * variants can differ materially from the broad static model-family table.</p>
 */
final class OpenCodeModelMetadataResolver {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, Optional<ModelMetadata>> CACHE = new ConcurrentHashMap<>();

    private OpenCodeModelMetadataResolver() {
    }

    static Optional<ModelMetadata> resolve(String providerId, String modelId) {
        return resolve(providerId, modelId, DEFAULT_TIMEOUT);
    }

    static Optional<ModelMetadata> resolve(String providerId, String modelId, Duration timeout) {
        String provider = normalizeProvider(providerId, modelId);
        String model = normalizeModelId(provider, modelId);
        if (provider.isBlank() || model.isBlank()) {
            return Optional.empty();
        }

        String cacheKey = provider + "/" + model;
        return CACHE.computeIfAbsent(cacheKey, ignored -> fetch(provider, model, timeout));
    }

    private static Optional<ModelMetadata> fetch(String providerId, String modelId, Duration timeout) {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("kompile-opencode-models-", ".txt");
            ProcessBuilder pb = new ProcessBuilder(
                    opencodeExecutable(),
                    "models",
                    providerId,
                    "--verbose");
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            return parseVerboseModel(output, providerId, modelId);
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    static Optional<ModelMetadata> parseVerboseModel(String output, String providerId, String modelId) {
        String provider = normalizeProvider(providerId, modelId);
        String model = normalizeModelId(provider, modelId);
        if (output == null || output.isBlank() || provider.isBlank() || model.isBlank()) {
            return Optional.empty();
        }

        String[] lines = output.split("\\R");
        String lastHeader = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("{")) {
                lastHeader = line;
                continue;
            }

            StringBuilder json = new StringBuilder();
            int depth = 0;
            for (; i < lines.length; i++) {
                String jsonLine = lines[i];
                json.append(jsonLine).append('\n');
                depth += braceDelta(jsonLine);
                if (depth == 0 && json.length() > 1) {
                    break;
                }
            }

            Optional<ModelMetadata> parsed = parseJsonBlock(json.toString(), lastHeader, provider, model);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private static Optional<ModelMetadata> parseJsonBlock(String json,
                                                          String header,
                                                          String expectedProvider,
                                                          String expectedModel) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String provider = textOrDefault(node.path("providerID"), providerFromHeader(header));
            String model = textOrDefault(node.path("id"), modelFromHeader(header));
            if (!expectedProvider.equals(provider) || !expectedModel.equals(model)) {
                return Optional.empty();
            }

            JsonNode limit = node.path("limit");
            int context = limit.path("context").asInt(0);
            int output = limit.path("output").asInt(0);
            if (context <= 0 || output <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ModelMetadata(provider, model, context, output));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static int braceDelta(String line) {
        int delta = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = inString;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '{') delta++;
            else if (!inString && c == '}') delta--;
        }
        return delta;
    }

    private static String normalizeProvider(String providerId, String modelId) {
        if (providerId != null && !providerId.isBlank()) {
            return providerId.trim();
        }
        if (modelId != null) {
            int slash = modelId.indexOf('/');
            if (slash > 0) {
                return modelId.substring(0, slash).trim();
            }
        }
        return "";
    }

    private static String normalizeModelId(String providerId, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        String model = modelId.trim();
        String prefix = providerId + "/";
        if (!providerId.isBlank() && model.startsWith(prefix)) {
            return model.substring(prefix.length());
        }
        int slash = model.indexOf('/');
        if (slash > 0 && !providerId.isBlank()) {
            return model.substring(slash + 1);
        }
        return model;
    }

    private static String providerFromHeader(String header) {
        if (header == null) return "";
        int slash = header.indexOf('/');
        return slash > 0 ? header.substring(0, slash).trim() : "";
    }

    private static String modelFromHeader(String header) {
        if (header == null) return "";
        int slash = header.indexOf('/');
        return slash > 0 ? header.substring(slash + 1).trim() : header.trim();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank()
                ? node.asText()
                : defaultValue;
    }

    private static String opencodeExecutable() {
        return System.getProperty("kompile.opencode.executable", "opencode");
    }

    static void clearCacheForTests() {
        CACHE.clear();
    }

    record ModelMetadata(String providerId, String modelId, int contextWindow, int maxOutputTokens) {}
}
