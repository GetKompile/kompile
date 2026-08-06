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
package ai.kompile.cli.main.chat.roles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provider-specific launch defaults attached to a role.
 *
 * <p>The default thinking value applies to every model unless an exact entry in
 * {@code thinkingByModel} exists for the selected model.
 */
public final class RoleAgentDefaults {

    private final String model;
    private final String defaultThinking;
    private final Map<String, String> thinkingByModel;

    public RoleAgentDefaults(String model,
                             String defaultThinking,
                             Map<String, String> thinkingByModel) {
        this.model = cleanSingleLine(model, "model");
        this.defaultThinking = cleanSingleLine(defaultThinking, "default thinking");

        Map<String, String> normalized = new TreeMap<>();
        if (thinkingByModel != null) {
            thinkingByModel.forEach((modelName, thinking) -> {
                String selectedModel = cleanSingleLine(modelName, "thinking model");
                String selectedThinking = cleanSingleLine(thinking, "model thinking");
                if (selectedModel != null && selectedThinking != null) {
                    normalized.put(selectedModel, selectedThinking);
                }
            });
        }
        this.thinkingByModel = Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    public String getModel() {
        return model;
    }

    public String getDefaultThinking() {
        return defaultThinking;
    }

    public Map<String, String> getThinkingByModel() {
        return thinkingByModel;
    }

    /**
     * Resolve thinking for the final selected model.
     */
    public String resolveThinking(String selectedModel) {
        String normalizedModel = cleanSingleLine(selectedModel, "selected model");
        if (normalizedModel != null) {
            String modelSpecific = thinkingByModel.get(normalizedModel);
            if (modelSpecific != null) {
                return modelSpecific;
            }
        }
        return defaultThinking;
    }

    public boolean isEmpty() {
        return model == null && defaultThinking == null && thinkingByModel.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoleAgentDefaults that)) {
            return false;
        }
        return Objects.equals(model, that.model)
                && Objects.equals(defaultThinking, that.defaultThinking)
                && Objects.equals(thinkingByModel, that.thinkingByModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, defaultThinking, thinkingByModel);
    }

    @Override
    public String toString() {
        return "RoleAgentDefaults{model=" + display(model)
                + ", defaultThinking=" + display(defaultThinking)
                + ", thinkingByModel=" + thinkingByModel + "}";
    }

    private static String cleanSingleLine(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        for (int i = 0; i < cleaned.length(); i++) {
            if (Character.isISOControl(cleaned.charAt(i))) {
                throw new IllegalArgumentException(
                        "Role agent " + field + " must be a single-line value");
            }
        }
        return cleaned;
    }

    private static String display(String value) {
        return value == null ? "unset" : value;
    }
}
