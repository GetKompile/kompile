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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable, validated workflow policy applied to one chat turn. */
public record WorkflowPolicy(
        Mode mode,
        List<String> requiredSkills,
        boolean requirePlanBeforeMutation,
        int maxCorrections) {

    public static final int DEFAULT_MAX_CORRECTIONS = 2;
    public static final int HARD_MAX_CORRECTIONS = 8;

    public enum Mode {
        OFF,
        ADVISORY,
        ENFORCED;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) return OFF;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "disabled", "none" -> OFF;
                case "advisory", "advise", "guided", "guide" -> ADVISORY;
                case "enforced", "enforce", "strict" -> ENFORCED;
                default -> throw new IllegalArgumentException(
                        "Unknown workflow mode '" + value
                                + "'. Use off, advisory, or enforced.");
            };
        }
    }

    public WorkflowPolicy {
        mode = mode == null ? Mode.OFF : mode;
        requiredSkills = normalizeSkills(requiredSkills);
        maxCorrections = Math.max(0, Math.min(HARD_MAX_CORRECTIONS, maxCorrections));
    }

    public static WorkflowPolicy off() {
        return new WorkflowPolicy(Mode.OFF, List.of(), true, DEFAULT_MAX_CORRECTIONS);
    }

    public static WorkflowPolicy from(EnforcerConfig config) {
        if (config == null) return off();
        return new WorkflowPolicy(
                Mode.parse(config.getWorkflowMode()),
                config.getWorkflowRequiredSkills(),
                config.isWorkflowRequirePlanBeforeMutation(),
                config.getWorkflowMaxCorrections());
    }

    public WorkflowPolicy withSessionMode(Mode sessionMode, List<String> sessionSkills) {
        List<String> skills = sessionSkills == null ? requiredSkills : sessionSkills;
        return new WorkflowPolicy(sessionMode, skills,
                requirePlanBeforeMutation, maxCorrections);
    }

    public boolean active() {
        return mode != Mode.OFF;
    }

    public boolean enforced() {
        return mode == Mode.ENFORCED;
    }

    private static List<String> normalizeSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) continue;
            String normalized = skill.trim();
            if (!SkillRegistry.isValidName(normalized)) {
                throw new IllegalArgumentException(
                        "Invalid required workflow skill name: " + normalized);
            }
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }
}
