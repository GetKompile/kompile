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

package ai.kompile.app.web.controllers;

import ai.kompile.toolgateway.model.GatewayJudgeScore;
import ai.kompile.toolgateway.model.ToolGatewayConfig;
import ai.kompile.toolgateway.model.ToolGatewayRule;
import ai.kompile.toolgateway.model.ToolGatewayRulesConfig;
import ai.kompile.toolgateway.service.ToolGatewayConfigService;
import ai.kompile.toolgateway.service.ToolGatewayRulesProvider;
import ai.kompile.toolgateway.service.ToolGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing the tool gateway configuration and rules.
 */
@RestController
@RequestMapping("/api/tool-gateway")
@CrossOrigin(origins = "*")
public class ToolGatewayConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ToolGatewayConfigController.class);

    private final ToolGatewayConfigService configService;
    private final ToolGatewayRulesProvider rulesProvider;
    private final ToolGatewayService gatewayService;

    @Autowired
    public ToolGatewayConfigController(
            ToolGatewayConfigService configService,
            @Autowired(required = false) ToolGatewayRulesProvider rulesProvider,
            @Autowired(required = false) ToolGatewayService gatewayService
    ) {
        this.configService = configService;
        this.rulesProvider = rulesProvider;
        this.gatewayService = gatewayService;
    }

    /**
     * Get current tool gateway configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();

        config.put("available", true);
        config.put("enabled", configService.isEnabled());

        ToolGatewayConfig gwConfig = configService.getConfig();
        config.put("modelSource", gwConfig.getModelSource().name());
        config.put("failOpen", gwConfig.isFailOpen());
        config.put("evaluationTimeoutMs", gwConfig.getEvaluationTimeoutMs());
        config.put("verboseLogging", gwConfig.isVerboseLogging());
        config.put("hotReload", gwConfig.isHotReload());
        config.put("dryRun", gwConfig.isDryRun());
        config.put("judgeScoringEnabled", gwConfig.isJudgeScoringEnabled());

        if (rulesProvider != null) {
            ToolGatewayRulesConfig rulesConfig = rulesProvider.getConfig();
            config.put("defaultAction", rulesConfig.getDefaultAction().name());
            config.put("systemPrompt", rulesConfig.getSystemPrompt());
            config.put("rulesCount", rulesConfig.getRules().size());
            long enabledCount = rulesConfig.getRules().stream()
                    .filter(ToolGatewayRule::isEnabled).count();
            config.put("enabledRulesCount", enabledCount);
        }

        return ResponseEntity.ok(config);
    }

    /**
     * Update tool gateway configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody ToolGatewayConfig update) {
        logger.info("Updating tool gateway configuration");
        configService.save(update);

        // Update rules-level settings if provider is available
        if (rulesProvider != null) {
            rulesProvider.save();
        }

        return getConfig();
    }

    /**
     * Quick toggle gateway on/off via feature flags.
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Boolean> request) {
        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            configService.setEnabled(enabled);
            logger.info("Tool gateway {}", enabled ? "enabled" : "disabled");
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", configService.isEnabled()
        ));
    }

    /**
     * Get recent judge quality scores for gateway evaluations.
     */
    @GetMapping("/scores")
    public ResponseEntity<?> getScores() {
        if (gatewayService == null) {
            return ResponseEntity.ok(Map.of(
                    "scores", List.of(),
                    "message", "Gateway service not available"
            ));
        }

        List<GatewayJudgeScore> scores = gatewayService.getRecentScores();
        return ResponseEntity.ok(Map.of(
                "scores", scores,
                "count", scores.size(),
                "judgeScoringEnabled", configService.getConfig().isJudgeScoringEnabled()
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RULES CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/rules")
    public ResponseEntity<?> getRules() {
        if (rulesProvider == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "available", false,
                    "message", "Tool gateway rules provider not loaded"
            ));
        }

        return ResponseEntity.ok(rulesProvider.getAllRules());
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> addRule(@RequestBody ToolGatewayRule rule) {
        if (rulesProvider == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Tool gateway rules provider not loaded"
            ));
        }

        rulesProvider.addRule(rule);
        rulesProvider.save();
        logger.info("Added tool gateway rule: {}", rule.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "ruleId", rule.getId(),
                "totalRules", rulesProvider.getAllRules().size()
        ));
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable String ruleId) {
        if (rulesProvider == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Tool gateway rules provider not loaded"
            ));
        }

        boolean removed = rulesProvider.removeRule(ruleId);
        if (removed) {
            rulesProvider.save();
            logger.info("Deleted tool gateway rule: {}", ruleId);
        }

        return ResponseEntity.ok(Map.of(
                "success", removed,
                "ruleId", ruleId,
                "totalRules", rulesProvider.getAllRules().size()
        ));
    }

    @PostMapping("/rules/reload")
    public ResponseEntity<Map<String, Object>> reloadRules() {
        if (rulesProvider == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Tool gateway rules provider not loaded"
            ));
        }

        rulesProvider.reload();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "totalRules", rulesProvider.getAllRules().size()
        ));
    }
}
