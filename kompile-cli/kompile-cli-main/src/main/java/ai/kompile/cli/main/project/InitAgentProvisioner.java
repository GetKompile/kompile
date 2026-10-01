/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.CodeNavigationGuidance;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.mcp.McpToolInjectionSupport;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot init-time provisioner that makes coding agents work in a project.
 *
 * <p>Call {@link #provision(Path, int, int)} from {@code kompile project init}.
 * Everything degrades gracefully: any single step failure becomes a warning,
 * never an exception. Safe to call from scripts (non-interactive).</p>
 *
 * <p>Steps performed:
 * <ol>
 *   <li>Detect the six known agent CLIs on PATH.</li>
 *   <li>Write a persistent {@code .mcp.json} in {@code projectRoot} with
 *       the stdio entry (kompile CLI), the app SSE entry, and the staging SSE entry.</li>
 *   <li>If opencode is installed, write the kompile stdio entry into the
 *       project's opencode config (format-version-aware).</li>
 *   <li>Write {@code AGENTS.md} if it does not already exist, composing the
 *       classpath template with a project-specific preamble. An existing
 *       {@code AGENTS.md} only gets its code-navigation section added or brought
 *       up to date; everything outside that section is kept byte for byte.</li>
 *   <li>When no CLI agent is found, warn with install links for the supported agent CLIs.</li>
 * </ol>
 */
public final class InitAgentProvisioner {

    private static final ObjectMapper OM = JsonUtils.standardMapper();

    // Six commands from cli-agents.json — in canonical order.
    private static final List<AgentSpec> KNOWN_AGENTS = List.of(
            new AgentSpec("claude",   "claude-cli",  "https://claude.ai/code",   true),
            new AgentSpec("codex",    "codex-cli",   "https://github.com/openai/codex", false),
            new AgentSpec("gemini",   "gemini-cli",  "https://github.com/google-gemini/gemini-cli", false),
            new AgentSpec("opencode", "opencode-cli","https://opencode.ai",       false),
            new AgentSpec("qwen",     "qwen-cli",    "https://github.com/QwenLM/qwen-code", false),
            new AgentSpec("pi",       "pi-cli",      "https://github.com/getcursor/cursor-api", false)
    );

    private static final String AGENTS_MD_TEMPLATE = "templates/AGENTS.md";

    /**
     * Delimit the code-navigation section init keeps current in {@code AGENTS.md}. They differ
     * from the managed system-prompt and skills blocks, so a session's cleanup never removes it.
     */
    static final String CODE_NAVIGATION_BEGIN = "<!-- BEGIN KOMPILE CODE NAVIGATION -->";
    static final String CODE_NAVIGATION_END = "<!-- END KOMPILE CODE NAVIGATION -->";

    // Package-private: allows tests to override the launcher path without touching env (JVM-global).
    static String launcherPathOverride = null;

    // Package-private: lets tests search their own directory instead of PATH, so a test never
    // starts the agent CLIs installed on the machine.
    static String agentSearchPathOverride = null;

    // Package-private: how long one `<agent> --version` may run before it is killed.
    static Duration versionProbeTimeout = Duration.ofSeconds(5);

    private InitAgentProvisioner() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Provision agent tooling for the given project root.
     *
     * @param projectRoot  absolute path to the project root (where .mcp.json is written)
     * @param appPort      HTTP port of the running kompile-app (used for the app SSE entry)
     * @param stagingPort  HTTP port of the kompile-model-staging sidecar
     * @return a result object with summary lines, warnings, and an availability flag
     */
    public static AgentProvisionResult provision(Path projectRoot, int appPort, int stagingPort) {
        List<String> summary  = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ── 1. Detect CLI agents ──────────────────────────────────────────────
        List<DetectedAgent> detected = detectAgents(summary, warnings);
        boolean anyCliFound = detected.stream().anyMatch(d -> d.found);

        // ── 2. Persistent .mcp.json ───────────────────────────────────────────
        try {
            writePersistentMcpConfig(projectRoot, appPort, stagingPort);
            summary.add("  .mcp.json: stdio + app SSE + staging SSE entries written to "
                    + projectRoot.resolve(".mcp.json"));
        } catch (Exception e) {
            warnings.add("Could not write .mcp.json: " + e.getMessage());
        }

        // ── 3. opencode config ────────────────────────────────────────────────
        boolean opencodeFound = detected.stream()
                .anyMatch(d -> d.command.equals("opencode") && d.found);
        if (opencodeFound) {
            try {
                writeOpencodeConfig(projectRoot);
                summary.add("  opencode config: kompile entry written");
            } catch (Exception e) {
                warnings.add("Could not write opencode config: " + e.getMessage());
            }
        }

        // ── 3b. Per-project CLI LLM config ───────────────────────────────────────
        try {
            writeProjectCliLlmConfig(projectRoot, opencodeFound);
            if (opencodeFound) {
                summary.add("  cli-llm-config.json: opencode entry written to "
                        + projectRoot.resolve("config/cli-llm-config.json"));
            }
        } catch (Exception e) {
            warnings.add("Could not write cli-llm-config.json: " + e.getMessage());
        }

        // ── 4. AGENTS.md ──────────────────────────────────────────────────────
        try {
            Path agentsMd = projectRoot.resolve("AGENTS.md");
            if (!Files.exists(agentsMd)) {
                writeAgentsMd(agentsMd, projectRoot, appPort, stagingPort);
                summary.add("  AGENTS.md: written");
            } else if (mergeCodeNavigation(agentsMd)) {
                summary.add("  AGENTS.md: already exists — code-navigation section written, the rest left untouched");
            } else {
                summary.add("  AGENTS.md: already exists — left untouched");
            }
        } catch (Exception e) {
            warnings.add("Could not write AGENTS.md: " + e.getMessage());
        }

        // ── 5. No agent CLI found ─────────────────────────────────────────────
        if (!anyCliFound) {
            warnings.add("No agent CLI found.");
            warnings.add("  Install a CLI agent: https://claude.ai/code  |  https://opencode.ai  |  https://github.com/google-gemini/gemini-cli");
        }

        return new AgentProvisionResult(
                List.copyOf(summary),
                anyCliFound,
                List.copyOf(warnings)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — CLI detection
    // ─────────────────────────────────────────────────────────────────────────

    private static List<DetectedAgent> detectAgents(List<String> summary, List<String> warnings) {
        // Prefer CliAgentRegistry for the PATH scan (same logic as InitProjectCommand).
        // If the resource is missing (shouldn't happen — it's in kompile-app-core which is
        // on our compile-time classpath), fall back to a local PATH probe.
        List<CliAgentRegistry.CliAgentDef> defs = loadCliAgentDefs();
        boolean useRegistry = !defs.isEmpty();

        summary.add("Agent CLI detection (" + (useRegistry ? "CliAgentRegistry" : "local PATH probe") + "):");

        List<DetectedAgent> result = new ArrayList<>();
        for (AgentSpec spec : KNOWN_AGENTS) {
            DetectedAgent det;
            if (useRegistry) {
                det = detectViaRegistry(spec, defs);
            } else {
                det = detectViaPathProbe(spec);
            }
            result.add(det);

            String line = "  " + (det.found ? "✓" : "✗") + " " + det.command;
            if (det.found && det.version != null) line += " (" + det.version + ")";
            if (spec.isDefault) line += " — default";
            if (!det.found) line += " — not installed (" + spec.installUrl + ")";
            summary.add(line);
        }
        return result;
    }

    /**
     * Returns the CliAgentDef list. Returns empty list if the resource is unavailable.
     * We use the raw DTO rather than AgentProvider so we stay self-contained in cli-main.
     */
    private static List<CliAgentRegistry.CliAgentDef> loadCliAgentDefs() {
        try {
            return CliAgentRegistry.loadAll().stream()
                    .map(p -> {
                        // We only need command, which AgentProvider exposes.
                        CliAgentRegistry.CliAgentDef d = new CliAgentRegistry.CliAgentDef();
                        d.command = p.getCommand();
                        d.name    = p.getName();
                        return d;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static DetectedAgent detectViaRegistry(AgentSpec spec,
            List<CliAgentRegistry.CliAgentDef> defs) {
        // Use the same PATH-scan logic CliAgentRegistry.detectFirstAvailable() uses,
        // but for each individual command so we get per-agent found/not-found status.
        String path = agentSearchPath();
        if (path == null) return new DetectedAgent(spec.command, false, null);
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, spec.command);
            if (candidate.canExecute()) {
                String version = tryGetVersion(candidate.getPath());
                return new DetectedAgent(spec.command, true, version);
            }
        }
        return new DetectedAgent(spec.command, false, null);
    }

    private static DetectedAgent detectViaPathProbe(AgentSpec spec) {
        String path = agentSearchPath();
        if (path == null) return new DetectedAgent(spec.command, false, null);
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, spec.command);
            if (candidate.canExecute()) {
                String version = tryGetVersion(candidate.getPath());
                return new DetectedAgent(spec.command, true, version);
            }
        }
        return new DetectedAgent(spec.command, false, null);
    }

    /**
     * Runs {@code <executable> --version} and returns the first non-blank line it printed, or
     * null when it fails or outlives {@link #versionProbeTimeout}. The probe is killed then, so
     * a CLI that never answers cannot hold up {@code project init}.
     */
    private static String tryGetVersion(String executable) {
        String out = McpToolInjection.probeOutput(versionProbeTimeout, executable, "--version");
        if (out == null) {
            return null;
        }
        return out.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String agentSearchPath() {
        return agentSearchPathOverride != null ? agentSearchPathOverride : System.getenv("PATH");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Persistent .mcp.json
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Write or merge persistent kompile entries into {@code <projectRoot>/.mcp.json}.
     *
     * <p>Three entries are written:
     * <ul>
     *   <li>{@code "kompile"} — stdio entry (same shape as McpToolInjection.writeConfig)</li>
     *   <li>{@code "kompile-app"} — SSE entry for the app backend</li>
     *   <li>{@code "kompile-model-staging"} — SSE entry for the staging sidecar</li>
     * </ul>
     *
     * <p>Pre-existing unrelated entries are preserved (merge semantics, not clobber).
     * No backup/restore semantics — this is a persistent write.</p>
     */
    static void writePersistentMcpConfig(Path projectRoot, int appPort, int stagingPort)
            throws IOException {
        Path mcpJson = projectRoot.resolve(".mcp.json");

        ObjectNode root;
        if (Files.exists(mcpJson)) {
            try {
                root = (ObjectNode) OM.readTree(Files.readString(mcpJson));
            } catch (Exception e) {
                // Unparseable — start fresh, warn via caller
                root = OM.createObjectNode();
            }
        } else {
            root = OM.createObjectNode();
        }

        ObjectNode mcpServers;
        if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
            mcpServers = (ObjectNode) root.get("mcpServers");
        } else {
            mcpServers = root.putObject("mcpServers");
        }

        // ── kompile stdio entry ────────────────────────────────────────────
        // Shape mirrors McpToolInjectionSupport.createStdioConfig (command + args + cwd).
        String launcherCmd  = resolvedLauncherCommand();
        List<String> launcherArgs = resolvedLauncherArgs(projectRoot);
        ObjectNode kompile = mcpServers.putObject("kompile");
        if (launcherCmd != null && launcherArgs != null) {
            kompile.put("command", launcherCmd);
            ArrayNode argsArr = kompile.putArray("args");
            for (String a : launcherArgs) {
                argsArr.add(a);
            }
            kompile.put("cwd", projectRoot.toAbsolutePath().normalize().toString());
        } else {
            // No launcher — write a stub so the SSE entries still appear
            kompile.put("command", "kompile");
            kompile.putArray("args").add("mcp-stdio").add("--work-dir").add(projectRoot.toString());
        }

        // ── kompile-app SSE entry ──────────────────────────────────────────
        // Shape mirrors ProjectServiceCommand.addSseEntryToMcpJson:
        // { "type": "sse", "url": "http://localhost:<port>/mcp/sse" }
        String appSseUrl = "http://localhost:" + appPort + "/mcp/sse";
        ObjectNode appEntry = mcpServers.putObject("kompile-app");
        appEntry.put("type", "sse");
        appEntry.put("url", appSseUrl);

        // ── kompile-model-staging SSE entry ───────────────────────────────
        String stagingSseUrl = "http://localhost:" + stagingPort + "/mcp/sse";
        ObjectNode stagingEntry = mcpServers.putObject("kompile-model-staging");
        stagingEntry.put("type", "sse");
        stagingEntry.put("url", stagingSseUrl);

        Files.createDirectories(mcpJson.getParent());
        Files.writeString(mcpJson, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /**
     * Returns the resolved CLI binary command, honouring the package-private test override first.
     * Returns null if no launcher can be found.
     */
    private static String resolvedLauncherCommand() {
        if (launcherPathOverride != null && !launcherPathOverride.isBlank()) {
            return launcherPathOverride;
        }
        return McpToolInjectionSupport.findCliLauncherCommand();
    }

    /**
     * Returns the full args list (mcp-stdio, --work-dir, …) for the resolved launcher.
     * Returns null if no launcher is available.
     */
    private static List<String> resolvedLauncherArgs(Path workingDir) {
        if (launcherPathOverride != null && !launcherPathOverride.isBlank()) {
            // Mirrors CliLauncher.buildArgs(Path) — prepend nothing (no prefixArgs)
            return List.of("mcp-stdio", "--work-dir",
                    workingDir.toAbsolutePath().normalize().toString());
        }
        return McpToolInjectionSupport.findCliLauncherArgs(workingDir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 — opencode config
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3b — Per-project CLI LLM config
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a per-project {@code config/cli-llm-config.json} inside the project root
     * when opencode is detected among the CLI agents.
     *
     * <p>Uses merge-not-clobber semantics: if the file already exists and the
     * {@code "command"} field is non-blank, the file is left completely alone.
     * If {@code opencodeDetected} is false, no file is written.</p>
     *
     * <p>The JSON written matches {@code CliAgentLlmConfigService.CliLlmConfig}:
     * <pre>{ "enabled": true, "command": "opencode", "skipPermissions": true,
     *   "timeoutSeconds": 120, "agentModels": {} }</pre>
     *
     * <p>Package-private so tests can call it directly without going through
     * {@link #provision}.</p>
     */
    static void writeProjectCliLlmConfig(Path projectRoot, boolean opencodeDetected)
            throws IOException {
        if (!opencodeDetected) {
            return;
        }
        Path configDir  = projectRoot.resolve("config");
        Path configFile = configDir.resolve("cli-llm-config.json");
        Files.createDirectories(configDir);

        // Merge-not-clobber: if file exists and already has a non-blank command, leave it.
        if (Files.exists(configFile)) {
            try {
                JsonNode existing = OM.readTree(Files.readString(configFile));
                String existingCommand = existing.path("command").asText(null);
                if (existingCommand != null && !existingCommand.isBlank()) {
                    return; // pre-existing command — do not overwrite
                }
            } catch (Exception e) {
                // Unparseable — fall through and overwrite
            }
        }

        ObjectNode node = OM.createObjectNode();
        node.put("enabled", true);
        node.put("command", "opencode");
        node.put("skipPermissions", true);
        node.put("timeoutSeconds", 120);
        node.putObject("agentModels"); // empty object — runtime discovery fills it
        Files.writeString(configFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    /**
     * Writes the kompile stdio entry into the project-local opencode config,
     * using the format appropriate for the installed opencode version.
     * Reuses McpToolInjection.isCrushFormat() and writeCrushConfig() semantics
     * (no transient backup — we want the entry to persist).
     */
    private static void writeOpencodeConfig(Path projectRoot) throws IOException {
        String launcherCmd  = resolvedLauncherCommand();
        List<String> launcherArgs = resolvedLauncherArgs(projectRoot);
        boolean crush = McpToolInjection.detectCrushFormat();

        if (crush) {
            // 1.x: opencode.json, "mcp" key
            Path cfg = projectRoot.resolve("opencode.json");
            ObjectNode root;
            if (Files.exists(cfg)) {
                try {
                    root = (ObjectNode) OM.readTree(Files.readString(cfg));
                } catch (Exception e) {
                    root = OM.createObjectNode();
                }
            } else {
                root = OM.createObjectNode();
            }
            ObjectNode mcp;
            if (root.has("mcp") && root.get("mcp").isObject()) {
                mcp = (ObjectNode) root.get("mcp");
            } else {
                mcp = root.putObject("mcp");
            }
            ObjectNode entry = mcp.putObject("kompile");
            entry.put("enabled", true);
            entry.put("type", "local");
            ArrayNode cmdArr = entry.putArray("command");
            if (launcherCmd != null && launcherArgs != null) {
                cmdArr.add(launcherCmd);
                for (String a : launcherArgs) {
                    cmdArr.add(a);
                }
            } else {
                cmdArr.add("kompile").add("mcp-stdio").add("--work-dir").add(projectRoot.toString());
            }
            Files.writeString(cfg, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } else {
            // 0.x legacy: .opencode.json, "mcpServers" key — mirrors writeConfig stdio shape
            Path cfg = projectRoot.resolve(".opencode.json");
            ObjectNode root;
            if (Files.exists(cfg)) {
                try {
                    root = (ObjectNode) OM.readTree(Files.readString(cfg));
                } catch (Exception e) {
                    root = OM.createObjectNode();
                }
            } else {
                root = OM.createObjectNode();
            }
            ObjectNode servers;
            if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                servers = (ObjectNode) root.get("mcpServers");
            } else {
                servers = root.putObject("mcpServers");
            }
            ObjectNode kompile = servers.putObject("kompile");
            if (launcherCmd != null && launcherArgs != null) {
                kompile.put("command", launcherCmd);
                ArrayNode argsArr = kompile.putArray("args");
                for (String a : launcherArgs) {
                    argsArr.add(a);
                }
            } else {
                kompile.put("command", "kompile");
                kompile.putArray("args").add("mcp-stdio").add("--work-dir").add(projectRoot.toString());
            }
            Files.writeString(cfg, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 — AGENTS.md
    // ─────────────────────────────────────────────────────────────────────────

    private static void writeAgentsMd(Path target, Path projectRoot, int appPort, int stagingPort)
            throws IOException {
        String template = loadAgentsMdTemplate();
        String projectName = projectRoot.getFileName() != null
                ? projectRoot.getFileName().toString()
                : projectRoot.toAbsolutePath().normalize().toString();

        StringBuilder sb = new StringBuilder();

        // ── project-specific preamble ─────────────────────────────────────
        sb.append("# Agent Instructions — ").append(projectName).append("\n\n");
        sb.append("This file was generated by `kompile project init`.\n\n");
        sb.append("## Project Details\n\n");
        sb.append("| Item | Value |\n");
        sb.append("|------|-------|\n");
        sb.append("| Project name | ").append(projectName).append(" |\n");
        sb.append("| Config file | `kompile.project.json` |\n");
        sb.append("| Scripts | `scripts/` |\n");
        sb.append("| Kompile app port | ").append(appPort).append(" |\n");
        sb.append("| Staging sidecar port | ").append(stagingPort).append(" |\n");
        sb.append("| MCP SSE (app) | `http://localhost:").append(appPort).append("/mcp/sse` |\n");
        sb.append("| MCP SSE (staging) | `http://localhost:").append(stagingPort).append("/mcp/sse` |\n");
        sb.append("\n");
        sb.append("The project's `.mcp.json` registers three MCP servers: `kompile` (stdio CLI),\n");
        sb.append("`kompile-app` (SSE on port ").append(appPort).append("), and");
        sb.append(" `kompile-model-staging` (SSE on port ").append(stagingPort).append(").\n\n");
        sb.append("---\n\n");

        // ── shared tool-instruction template ─────────────────────────────
        if (template != null) {
            sb.append(template);
        } else {
            sb.append("<!-- kompile tool instructions template not found on classpath -->\n");
        }

        Files.writeString(target, sb.toString());
    }

    private static String loadAgentsMdTemplate() {
        try (InputStream is = InitAgentProvisioner.class.getClassLoader()
                .getResourceAsStream(AGENTS_MD_TEMPLATE)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** The marked code-navigation section, exactly as the AGENTS.md template carries it. */
    static String codeNavigationSection() {
        return CODE_NAVIGATION_BEGIN + "\n## CODE NAVIGATION\n\n"
                + CodeNavigationGuidance.RULE + "\n" + CODE_NAVIGATION_END;
    }

    /**
     * Add the code-navigation section to an existing {@code AGENTS.md}, or bring the last marked
     * one up to date. Text outside the markers is kept byte for byte. Searching from the last
     * BEGIN means a BEGIN whose END was deleted never makes the replacement swallow user text.
     *
     * @return whether the file changed
     */
    static boolean mergeCodeNavigation(Path agentsMd) throws IOException {
        String content = Files.readString(agentsMd);
        String section = codeNavigationSection();
        int begin = content.lastIndexOf(CODE_NAVIGATION_BEGIN);
        int end = begin < 0 ? -1 : content.indexOf(CODE_NAVIGATION_END, begin);
        String merged;
        if (end >= 0) {
            merged = content.substring(0, begin) + section
                    + content.substring(end + CODE_NAVIGATION_END.length());
        } else {
            String separator = content.isEmpty() || content.endsWith("\n\n") ? ""
                    : content.endsWith("\n") ? "\n" : "\n\n";
            merged = content + separator + section + "\n";
        }
        if (merged.equals(content)) {
            return false;
        }
        Files.writeString(agentsMd, merged);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal types
    // ─────────────────────────────────────────────────────────────────────────

    private record AgentSpec(String command, String registryName, String installUrl, boolean isDefault) {}

    private record DetectedAgent(String command, boolean found, String version) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public result type
    // ─────────────────────────────────────────────────────────────────────────

    /** Immutable result returned by {@link #provision}. */
    public record AgentProvisionResult(
            List<String> summaryLines,
            boolean anyAgentAvailable,
            List<String> warnings
    ) {}
}
