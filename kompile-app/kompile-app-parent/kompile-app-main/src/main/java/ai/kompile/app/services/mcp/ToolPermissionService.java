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

package ai.kompile.app.services.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Service for managing tool-level permissions.
 * <p>
 * Supports per-category and per-tool allow/deny rules with JSON persistence.
 * Tool-level rules override category-level rules, which override the default.
 */
@Service
public class ToolPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionService.class);
    private static final String CONFIG_FILE = "tool-permissions.json";

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private volatile ToolPermissionConfig config = new ToolPermissionConfig();

    public enum PermissionLevel {
        ALLOW, DENY
    }

    @PostConstruct
    public void initialize() {
        loadConfig();
    }

    /**
     * Check if a tool is allowed based on tool override -> category rule -> default.
     */
    public boolean isToolAllowed(String toolName, String category) {
        // Tool-level override takes highest priority
        PermissionLevel toolRule = config.getToolRules().get(toolName);
        if (toolRule != null) {
            return toolRule == PermissionLevel.ALLOW;
        }

        // Category-level rule
        if (category != null) {
            PermissionLevel categoryRule = config.getCategoryRules().get(category);
            if (categoryRule != null) {
                return categoryRule == PermissionLevel.ALLOW;
            }
        }

        // Default
        return config.getDefaultPermission() == PermissionLevel.ALLOW;
    }

    public ToolPermissionConfig getConfig() {
        return config;
    }

    public void setDefaultPermission(PermissionLevel level) {
        config.setDefaultPermission(level);
        saveConfig();
    }

    public void setCategoryRule(String category, PermissionLevel level) {
        config.getCategoryRules().put(category, level);
        saveConfig();
    }

    public void removeCategoryRule(String category) {
        config.getCategoryRules().remove(category);
        saveConfig();
    }

    public void setToolRule(String toolName, PermissionLevel level) {
        config.getToolRules().put(toolName, level);
        saveConfig();
    }

    public void removeToolRule(String toolName) {
        config.getToolRules().remove(toolName);
        saveConfig();
    }

    /**
     * Bulk update: apply multiple category and tool rules at once.
     */
    public void bulkUpdate(Map<String, PermissionLevel> categoryRules, Map<String, PermissionLevel> toolRules) {
        if (categoryRules != null) {
            config.getCategoryRules().putAll(categoryRules);
        }
        if (toolRules != null) {
            config.getToolRules().putAll(toolRules);
        }
        saveConfig();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════════

    Path getConfigDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config");
    }

    private Path getConfigPath() {
        return getConfigDir().resolve(CONFIG_FILE);
    }

    private void loadConfig() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            log.debug("No tool permissions config found at {}, using defaults", configPath);
            return;
        }

        try {
            config = objectMapper.readValue(configPath.toFile(), ToolPermissionConfig.class);
            log.info("Loaded tool permissions config: default={}, {} category rules, {} tool rules",
                    config.getDefaultPermission(),
                    config.getCategoryRules().size(),
                    config.getToolRules().size());
        } catch (Exception e) {
            log.error("Failed to load tool permissions config: {}", e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            Path configDir = getConfigDir();
            Files.createDirectories(configDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(getConfigPath().toFile(), config);
            log.debug("Saved tool permissions config");
        } catch (Exception e) {
            log.error("Failed to save tool permissions config: {}", e.getMessage());
        }
    }
}
