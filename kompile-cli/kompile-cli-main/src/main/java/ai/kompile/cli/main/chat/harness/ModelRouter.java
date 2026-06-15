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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.ChatConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model swap decision engine. Tracks rolling quality scores per model,
 * manages cooldowns, and decides when to swap models based on performance
 * thresholds or rate-limit errors.
 */
public class ModelRouter {

    private final HarnessConfig config;
    private final ModelPerformanceStore store;
    private final ChatConfig chatConfig;

    // Rolling quality scores per model (bounded deque)
    private final Map<String, Deque<Float>> recentScoresByModel = new ConcurrentHashMap<>();

    // Cooldown tracking: model -> epoch ms when cooldown expires
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    // Current model override per agent (set by auto-swap)
    private final Map<String, String> modelOverrideByAgent = new ConcurrentHashMap<>();

    // Manual override suppression: counts down turns before auto-swap resumes
    private volatile int manualOverrideTurnsRemaining = 0;

    // Swap history for display
    private final List<SwapEvent> swapHistory = Collections.synchronizedList(new ArrayList<>());

    public ModelRouter(HarnessConfig config, ModelPerformanceStore store, ChatConfig chatConfig) {
        this.config = config;
        this.store = store;
        this.chatConfig = chatConfig;
    }

    /**
     * Called after every evaluated turn. Checks if a model swap is needed.
     *
     * @return the new model name if a swap was triggered, null otherwise
     */
    public String onTurnComplete(String agentName, String model, float qualityScore,
                                  long latencyMs, boolean hadRateLimit) {
        if (!config.isAutoSwapEnabled()) return null;

        // Decrement manual override counter
        if (manualOverrideTurnsRemaining > 0) {
            manualOverrideTurnsRemaining--;
            if (manualOverrideTurnsRemaining > 0) return null;
        }

        // Handle rate limit — immediate swap
        if (hadRateLimit && config.isRateLimitFallbackEnabled()) {
            cooldownUntil.put(model, System.currentTimeMillis() + config.getRateLimitCooldownMs());
            String fallback = pickNextAvailableModel(agentName, model, "general");
            if (fallback != null) {
                applySwap(agentName, model, fallback, "rate-limit");
                return fallback;
            }
            return null;
        }

        // Skip invalid scores
        if (qualityScore <= 0) return null;

        // Add to rolling window
        Deque<Float> scores = recentScoresByModel.computeIfAbsent(model,
                k -> new ArrayDeque<>(config.getRollingWindowSize()));
        scores.addLast(qualityScore);
        while (scores.size() > config.getRollingWindowSize()) {
            scores.removeFirst();
        }

        // Only evaluate when window is full (confidence gating)
        if (scores.size() < config.getRollingWindowSize()) return null;

        float avgScore = avg(scores);
        if (avgScore < config.getSwapThresholdScore()) {
            String taskType = JudgeLlmEvaluator.detectTaskType(agentName, null);
            String better = pickBestAlternativeModel(agentName, model, taskType);
            if (better != null && !better.equals(model)) {
                // Cooldown the underperforming model
                cooldownUntil.put(model, System.currentTimeMillis() + config.getQualityCooldownMs());
                applySwap(agentName, model, better,
                        "quality avg " + String.format("%.1f", avgScore) + "/5");
                return better;
            }
        }

        return null;
    }

    /**
     * Fast-path for rate-limit errors detected in streamDirectTurn.
     * Returns the fallback model, or null if none available.
     */
    public String onRateLimitError(String currentModel) {
        if (!config.isRateLimitFallbackEnabled()) return null;
        cooldownUntil.put(currentModel,
                System.currentTimeMillis() + config.getRateLimitCooldownMs());
        return pickNextAvailableModel(null, currentModel, "general");
    }

    /**
     * Check if the router has an override for this agent.
     */
    public String getCurrentModelFor(String agentName) {
        return modelOverrideByAgent.get(agentName);
    }

    /**
     * Called when the user manually changes the model.
     * Suppresses auto-swap for 5 turns.
     */
    public void setManualOverride(String newModel) {
        manualOverrideTurnsRemaining = 5;
        // Clear any auto-swap overrides since user is taking control
        modelOverrideByAgent.clear();
        // Reset rolling scores for the new model so it starts fresh
        recentScoresByModel.remove(newModel);
    }

    /**
     * Get the swap history for this session.
     */
    public List<SwapEvent> getSwapHistory() {
        return Collections.unmodifiableList(swapHistory);
    }

    /**
     * Check if a model is currently in cooldown.
     */
    public boolean isInCooldown(String model) {
        Long until = cooldownUntil.get(model);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(model);
            return false;
        }
        return true;
    }

    private void applySwap(String agentName, String fromModel, String toModel, String reason) {
        if (agentName != null) {
            modelOverrideByAgent.put(agentName, toModel);
        }
        SwapEvent event = new SwapEvent(agentName, fromModel, toModel, reason, System.currentTimeMillis());
        swapHistory.add(event);
    }

    private String pickBestAlternativeModel(String agentName, String currentModel, String taskType) {
        // 1. Check performance store for best model
        String best = store.getBestModelForTask(taskType, chatConfig.getProvider());
        if (best != null && !best.equals(currentModel) && !isInCooldown(best)) {
            return best;
        }

        // 2. Fall through to candidate list
        return pickNextAvailableModel(agentName, currentModel, taskType);
    }

    private String pickNextAvailableModel(String agentName, String currentModel, String taskType) {
        List<String> candidates = getCandidateModels();

        for (String candidate : candidates) {
            if (candidate.equals(currentModel)) continue;
            if (isInCooldown(candidate)) continue;
            return candidate;
        }

        return null;
    }

    private List<String> getCandidateModels() {
        // 1. User-configured swap candidates (highest priority)
        List<String> configured = config.getSwapCandidateModels();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }

        // 2. Default models for current provider
        String provider = chatConfig.getProvider();
        if (provider != null) {
            String[] defaults = ChatConfig.getDefaultModels(provider);
            if (defaults.length > 0) {
                return List.of(defaults);
            }
        }

        return List.of();
    }

    private static float avg(Deque<Float> values) {
        if (values.isEmpty()) return 0;
        float sum = 0;
        for (float v : values) sum += v;
        return sum / values.size();
    }

    /**
     * Record of a model swap event.
     */
    public record SwapEvent(String agentName, String fromModel, String toModel,
                            String reason, long timestampMs) {}
}
