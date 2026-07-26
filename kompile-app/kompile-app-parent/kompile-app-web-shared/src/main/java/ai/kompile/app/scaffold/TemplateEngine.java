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

package ai.kompile.app.scaffold;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple {{variable}} template engine. Reads template files from classpath resources,
 * performs variable substitution, and writes output files.
 *
 * Each template directory contains a manifest.json mapping template files to output paths.
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    /**
     * Processes all templates from a resource directory using the given context.
     *
     * @param templateResourcePath Classpath resource path (e.g., "templates/mobile/ios")
     * @param outputDir            Target directory to write generated files
     * @param context              Template variable context
     * @throws IOException if template processing fails
     */
    public void processTemplates(String templateResourcePath, Path outputDir, TemplateContext context) throws IOException {
        Map<String, String> variables = context.toMap();

        // Load manifest
        String manifestPath = templateResourcePath + "/manifest.json";
        JsonNode manifest = loadManifest(manifestPath);
        if (manifest == null) {
            throw new IOException("Template manifest not found: " + manifestPath);
        }

        JsonNode files = manifest.get("files");
        if (files == null || !files.isObject()) {
            throw new IOException("Template manifest missing 'files' section");
        }

        files.fieldNames().forEachRemaining(templateFile -> {
            String outputPath = files.get(templateFile).asText();
            // Substitute variables in the output path itself
            outputPath = substitute(outputPath, variables);

            try {
                String templateContent = loadTemplate(templateResourcePath + "/" + templateFile);
                if (templateContent == null) {
                    log.warn("Template file not found: {}", templateFile);
                    return;
                }

                String processedContent = substitute(templateContent, variables);

                Path fullOutputPath = outputDir.resolve(outputPath);
                Files.createDirectories(fullOutputPath.getParent());
                Files.writeString(fullOutputPath, processedContent, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Error processing template {}: {}", templateFile, e.getMessage());
            }
        });
    }

    /**
     * Substitutes all {{variable}} occurrences in text with values from the map.
     */
    public String substitute(String text, Map<String, String> variables) {
        if (text == null) return null;
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.getOrDefault(varName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Loads a template file from classpath resources.
     */
    private String loadTemplate(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads the manifest.json from classpath resources.
     */
    private JsonNode loadManifest(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return OBJECT_MAPPER.readTree(is);
        }
    }
}
