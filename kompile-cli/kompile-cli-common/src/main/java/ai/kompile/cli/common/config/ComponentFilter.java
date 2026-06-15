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

package ai.kompile.cli.common.config;

import java.util.*;

/**
 * Controls which components are included in a config archive export or import.
 *
 * <p>Components are organized into categories:
 * <ul>
 *   <li><b>kompile-app-configs</b> — ~/.kompile/config/*.json (app-index, batch-size, pipeline, etc.)</li>
 *   <li><b>kompile-chat-config</b> — ~/.kompile/chat-config.json (LLM provider, model, mode)</li>
 *   <li><b>kompile-harness-config</b> — ~/.kompile/harness-config.json (performance harness)</li>
 *   <li><b>kompile-other-configs</b> — ~/.kompile/staging-settings.json, perf-data.json</li>
 *   <li><b>system-prompts</b> — ~/.kompile/system-prompt.md and per-agent overrides</li>
 *   <li><b>claude</b> — Claude Code settings (~/.claude/)</li>
 *   <li><b>codex</b> — OpenAI Codex settings (~/.codex/)</li>
 *   <li><b>qwen</b> — Qwen Code settings (~/.qwen/)</li>
 *   <li><b>opencode</b> — OpenCode settings (~/.config/opencode/)</li>
 *   <li><b>gemini</b> — Gemini CLI settings (~/.gemini/)</li>
 * </ul>
 */
public class ComponentFilter {

    /** All component keys in display order. */
    public static final String[] ALL_COMPONENTS = {
            "kompile-app-configs",
            "kompile-chat-config",
            "kompile-harness-config",
            "kompile-other-configs",
            "system-prompts",
            "claude",
            "codex",
            "qwen",
            "opencode",
            "gemini",
            "pi"
    };

    /** Human-readable labels for each component. */
    public static final Map<String, String> COMPONENT_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kompile-app-configs",    "Kompile App Configs  — app-index, batch-size, pipeline, filters, etc.");
        m.put("kompile-chat-config",    "Kompile Chat Config  — LLM provider, model, chat mode settings");
        m.put("kompile-harness-config", "Kompile Harness      — performance harness / judge configuration");
        m.put("kompile-other-configs",  "Kompile Other        — staging-settings, perf-data");
        m.put("system-prompts",         "System Prompts       — global and per-agent system prompts");
        m.put("claude",                 "Claude Code          — ~/.claude/ settings");
        m.put("codex",                  "OpenAI Codex         — ~/.codex/ config & instructions");
        m.put("qwen",                   "Qwen Code            — ~/.qwen/ settings");
        m.put("opencode",              "OpenCode             — ~/.config/opencode/ config");
        m.put("gemini",                 "Gemini CLI           — ~/.gemini/ settings");
        m.put("pi",                     "Pi (Coding Agent)    — ~/.pi/ sessions & config");
        COMPONENT_LABELS = Collections.unmodifiableMap(m);
    }

    private final Set<String> enabled = new LinkedHashSet<>();

    /** Create a filter with all components enabled (default). */
    public static ComponentFilter all() {
        ComponentFilter f = new ComponentFilter();
        f.enabled.addAll(Arrays.asList(ALL_COMPONENTS));
        return f;
    }

    /** Create a filter with no components enabled. */
    public static ComponentFilter none() {
        return new ComponentFilter();
    }

    public void enable(String component) { enabled.add(component); }
    public void disable(String component) { enabled.remove(component); }
    public void toggle(String component) {
        if (enabled.contains(component)) enabled.remove(component);
        else enabled.add(component);
    }

    public boolean isEnabled(String component) { return enabled.contains(component); }
    public Set<String> getEnabled() { return Collections.unmodifiableSet(enabled); }

    public boolean includeKompileAppConfigs()    { return enabled.contains("kompile-app-configs"); }
    public boolean includeKompileChatConfig()    { return enabled.contains("kompile-chat-config"); }
    public boolean includeKompileHarnessConfig() { return enabled.contains("kompile-harness-config"); }
    public boolean includeKompileOtherConfigs()  { return enabled.contains("kompile-other-configs"); }
    public boolean includeSystemPrompts()        { return enabled.contains("system-prompts"); }
    public boolean includeProvider(String name)  { return enabled.contains(name); }

    /** True if at least one component is enabled. */
    public boolean hasAnyEnabled() { return !enabled.isEmpty(); }

    /**
     * Check whether a zip entry name passes this filter.
     * Used during import to skip entries whose component is disabled.
     */
    public boolean acceptsEntry(String entryName) {
        if (entryName.startsWith("kompile/config/")) return includeKompileAppConfigs();
        if ("kompile/chat-config.json".equals(entryName)) return includeKompileChatConfig();
        if ("kompile/harness-config.json".equals(entryName)) return includeKompileHarnessConfig();
        if (entryName.equals("kompile/staging-settings.json") ||
            entryName.equals("kompile/perf-data.json")) return includeKompileOtherConfigs();
        if (entryName.startsWith("kompile/system-prompt")) return includeSystemPrompts();
        if (entryName.startsWith("chat-providers/claude/")) return includeProvider("claude");
        if (entryName.startsWith("chat-providers/codex/")) return includeProvider("codex");
        if (entryName.startsWith("chat-providers/qwen/")) return includeProvider("qwen");
        if (entryName.startsWith("chat-providers/opencode/")) return includeProvider("opencode");
        if (entryName.startsWith("chat-providers/gemini/")) return includeProvider("gemini");
        if (entryName.startsWith("chat-providers/pi/")) return includeProvider("pi");
        return true; // unknown entries pass through
    }
}
