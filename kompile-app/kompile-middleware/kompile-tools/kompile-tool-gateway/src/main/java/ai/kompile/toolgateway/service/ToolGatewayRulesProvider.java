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

package ai.kompile.toolgateway.service;

import ai.kompile.toolgateway.model.ToolGatewayRule;
import ai.kompile.toolgateway.model.ToolGatewayRulesConfig;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads and manages the tool gateway rules from a JSON file.
 * <p>
 * Rules are loaded at startup and can be reloaded on demand via {@link #reload()}.
 * When hot-reload is enabled in config, rules are re-read from disk
 * on every evaluation.
 * </p>
 */
public class ToolGatewayRulesProvider {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayRulesProvider.class);
    private static final String DEFAULT_RULES_PATH = System.getProperty("user.home")
            + "/.kompile/config/tool-gateway-rules.json";

    private final ToolGatewayConfigService configService;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private volatile ToolGatewayRulesConfig config = new ToolGatewayRulesConfig();

    public ToolGatewayRulesProvider(ToolGatewayConfigService configService) {
        this.configService = configService;
    }

    @PostConstruct
    public void initialize() {
        reload();
    }

    /**
     * Reload rules from disk. Safe to call at any time.
     */
    public void reload() {
        Path rulesPath = Path.of(DEFAULT_RULES_PATH);
        if (!Files.exists(rulesPath)) {
            log.info("Tool gateway rules file not found at {}; using empty ruleset (default action: ALLOW)", rulesPath);
            config = new ToolGatewayRulesConfig();
            return;
        }

        try {
            config = objectMapper.readValue(rulesPath.toFile(), ToolGatewayRulesConfig.class);
            long enabledCount = config.getRules().stream().filter(ToolGatewayRule::isEnabled).count();
            log.info("Loaded {} tool gateway rules ({} enabled) from {}",
                    config.getRules().size(), enabledCount, rulesPath);
        } catch (Exception e) {
            log.error("Failed to load tool gateway rules from {}: {}", rulesPath, e.getMessage());
        }
    }

    /**
     * Persist the current config back to disk.
     */
    public void save() {
        Path rulesPath = Path.of(DEFAULT_RULES_PATH);
        try {
            Files.createDirectories(rulesPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(rulesPath.toFile(), config);
            log.debug("Saved tool gateway rules to {}", rulesPath);
        } catch (Exception e) {
            log.error("Failed to save tool gateway rules to {}: {}", rulesPath, e.getMessage());
        }
    }

    /**
     * Get the full config (for REST API exposure, etc.).
     */
    public ToolGatewayRulesConfig getConfig() {
        if (configService.getConfig().isHotReload()) {
            reload();
        }
        return config;
    }

    /**
     * Get only the enabled rules that match the given tool name,
     * sorted by priority descending (highest priority first).
     */
    public List<ToolGatewayRule> getMatchingRules(String toolName) {
        if (configService.getConfig().isHotReload()) {
            reload();
        }

        return config.getRules().stream()
                .filter(ToolGatewayRule::isEnabled)
                .filter(rule -> matchesToolPattern(rule, toolName))
                .sorted(Comparator.comparingInt(ToolGatewayRule::getPriority).reversed())
                .collect(Collectors.toList());
    }

    private boolean matchesToolPattern(ToolGatewayRule rule, String toolName) {
        if (rule.getToolPatterns() == null || rule.getToolPatterns().isEmpty()) {
            return true;
        }

        for (String pattern : rule.getToolPatterns()) {
            if (globMatches(pattern, toolName)) {
                return true;
            }
        }
        return false;
    }

    private boolean globMatches(String glob, String value) {
        if ("*".equals(glob)) {
            return true;
        }
        String regex = Pattern.quote(glob).replace("*", "\\E.*\\Q");
        regex = regex.replace("\\Q\\E", "");
        return Pattern.matches(regex, value);
    }

    public void addRule(ToolGatewayRule rule) {
        config.getRules().add(rule);
    }

    public boolean removeRule(String ruleId) {
        return config.getRules().removeIf(r -> ruleId.equals(r.getId()));
    }

    public List<ToolGatewayRule> getAllRules() {
        return Collections.unmodifiableList(config.getRules());
    }
}
