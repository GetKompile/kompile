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

package ai.kompile.cli.main.chat.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure Java rules loader for CLI-side tool gateway.
 * <p>
 * Loads rules from {@code ~/.kompile/config/tool-gateway-rules.json}
 * using raw Jackson parsing (no dependency on the kompile-tool-gateway module).
 * Implements the same glob matching logic as the server-side
 * {@code ToolGatewayRulesProvider}.
 * </p>
 */
public class CliToolGatewayRulesLoader {

    private static final Path DEFAULT_RULES_PATH = Path.of(
            System.getProperty("user.home"), ".kompile", "config", "tool-gateway-rules.json");

    private final ObjectMapper objectMapper;
    private volatile List<Map<String, Object>> rules = Collections.emptyList();
    private volatile String systemPrompt;

    public CliToolGatewayRulesLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        reload();
    }

    /**
     * Reload rules from disk.
     */
    @SuppressWarnings("unchecked")
    public void reload() {
        if (!Files.exists(DEFAULT_RULES_PATH)) {
            rules = Collections.emptyList();
            systemPrompt = null;
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(Files.readString(DEFAULT_RULES_PATH));

            systemPrompt = root.has("systemPrompt") ? root.get("systemPrompt").asText(null) : null;

            JsonNode rulesNode = root.path("rules");
            if (rulesNode.isArray()) {
                List<Map<String, Object>> loaded = new ArrayList<>();
                for (JsonNode ruleNode : rulesNode) {
                    Map<String, Object> rule = objectMapper.convertValue(ruleNode,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    loaded.add(rule);
                }
                rules = loaded;
            }
        } catch (Exception e) {
            System.err.println("[Gateway] Failed to load rules: " + e.getMessage());
        }
    }

    /**
     * Get rules that match the given tool name (enabled only, sorted by priority desc).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMatchingRules(String toolName) {
        List<Map<String, Object>> matching = new ArrayList<>();

        for (Map<String, Object> rule : rules) {
            // Skip disabled rules
            Object enabledObj = rule.get("enabled");
            if (enabledObj instanceof Boolean && !(Boolean) enabledObj) {
                continue;
            }

            // Check tool patterns
            Object patternsObj = rule.get("toolPatterns");
            if (patternsObj instanceof List) {
                List<String> patterns = (List<String>) patternsObj;
                if (patterns.isEmpty() || matchesAny(patterns, toolName)) {
                    matching.add(rule);
                }
            } else {
                // No patterns = match all
                matching.add(rule);
            }
        }

        // Sort by priority descending
        matching.sort((a, b) -> {
            int pa = a.containsKey("priority") ? ((Number) a.get("priority")).intValue() : 0;
            int pb = b.containsKey("priority") ? ((Number) b.get("priority")).intValue() : 0;
            return Integer.compare(pb, pa);
        });

        return matching;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    private boolean matchesAny(List<String> patterns, String toolName) {
        for (String pattern : patterns) {
            if (globMatches(pattern, toolName)) return true;
        }
        return false;
    }

    private boolean globMatches(String glob, String value) {
        if ("*".equals(glob)) return true;
        String regex = Pattern.quote(glob).replace("*", "\\E.*\\Q");
        regex = regex.replace("\\Q\\E", "");
        return Pattern.matches(regex, value);
    }
}
