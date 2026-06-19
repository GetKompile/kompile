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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.config.BuildPreset;
import ai.kompile.cli.main.build.config.ModuleCatalog;
import ai.kompile.cli.main.chat.agent.AgentFlagOverrides;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent-guided project initialization. Uses an external CLI agent (claude, codex, gemini, etc.)
 * to recommend project configuration based on a conversational understanding of the user's needs.
 */
public class AgentGuidedInit {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String WHITE = "\033[37m";

    private static final String JSON_START_SENTINEL = "---KOMPILE_CONFIG_JSON---";
    private static final String JSON_END_SENTINEL = "---KOMPILE_CONFIG_JSON_END---";

    // Loaded dynamically from cli-agents.json via CliAgentRegistry
    private static final int AGENT_TIMEOUT_SECONDS = 180;

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private AgentGuidedInit() {}

    /**
     * Run the agent-guided init flow. Returns a WizardResult on success, null on cancel/failure.
     */
    public static InitProjectWizard.WizardResult run(String preferredAgent) throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println(BOLD + "  Agent-Guided Project Setup" + RESET);
            System.out.println(DIM + "  An AI agent will help design your project configuration." + RESET);
            System.out.println();

            // Detect available agents
            Map<String, String> available = detectAvailableAgents();
            if (available.isEmpty() && (preferredAgent == null || preferredAgent.equals("auto"))) {
                System.out.println(RED + "  No AI agents found on PATH." + RESET);
                System.out.println(DIM + "  Install one of: claude, codex, gemini, qwen, opencode, pi" + RESET);
                System.out.println(DIM + "  Or use --wizard for manual setup." + RESET);
                return null;
            }

            // Show detected agents
            System.out.println("  Detecting available AI agents...");
            for (String name : ai.kompile.core.agent.CliAgentRegistry.commandNames()) {
                String path = SubprocessAgentRunner.resolveAgentBinary(name);
                String status = path != null ? GREEN + "found" + RESET : DIM + "not found" + RESET;
                System.out.printf("    %-16s %s%n", name, status);
            }
            System.out.println();

            // Choose agent
            String agentName = chooseAgent(reader, available, preferredAgent);
            if (agentName == null) return null;

            // Get project name
            String projectName = prompt(reader,
                    "  Project name" + DIM + " (lowercase, hyphens ok)" + RESET + ": ");
            if (projectName == null || projectName.isBlank()) {
                System.out.println(RED + "  Project name is required." + RESET);
                return null;
            }
            projectName = projectName.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");

            // Get output directory
            String outputDirStr = prompt(reader,
                    "  Output directory" + DIM + " (Enter for current dir)" + RESET + ": ");
            File outputDir = (outputDirStr == null || outputDirStr.isBlank())
                    ? new File(".").getCanonicalFile()
                    : new File(outputDirStr).getCanonicalFile();

            // Get description
            System.out.println();
            System.out.println(CYAN + "  Describe what you're building." + RESET);
            System.out.println(DIM + "  Examples: 'A knowledge base for D&D campaign PDFs and web resources'" + RESET);
            System.out.println(DIM + "            'A code search tool for our Java monorepo'" + RESET);
            System.out.println(DIM + "            'Document Q&A for legal contracts using OpenAI'" + RESET);
            System.out.println();
            String description = prompt(reader, "  What are you building? ");
            if (description == null || description.isBlank()) {
                System.out.println(RED + "  Description is required for agent-guided setup." + RESET);
                return null;
            }

            // Build prompt and invoke agent
            System.out.println();
            System.out.println("  Asking " + BOLD + agentName + RESET + " for project recommendations...");
            System.out.println(DIM + "  (this may take 30-60 seconds)" + RESET);

            String agentPrompt = buildAgentPrompt(projectName, description, available.keySet());
            String binary = available.get(agentName);
            String rawOutput = invokeAgent(binary, agentName, agentPrompt);

            if (rawOutput == null || rawOutput.isBlank()) {
                System.out.println(RED + "  Agent returned no output." + RESET);
                return offerFallback(reader);
            }

            // Extract and parse JSON
            String json = extractJsonBlock(rawOutput);
            if (json == null) {
                System.out.println(RED + "  Could not find configuration JSON in agent output." + RESET);
                System.out.println(DIM + "  Raw output (truncated):" + RESET);
                System.out.println(DIM + "  " + rawOutput.substring(0, Math.min(500, rawOutput.length())) + RESET);
                return offerFallback(reader);
            }

            InitProjectWizard.WizardResult result;
            try {
                result = parseAgentJson(json, projectName, outputDir);
            } catch (Exception e) {
                System.out.println(RED + "  Failed to parse agent configuration: " + e.getMessage() + RESET);
                return offerFallback(reader);
            }

            // Show confirmation
            String reasoning = extractReasoning(json);
            if (confirmRecommendation(reader, result, agentName, reasoning)) {
                return result;
            }

            // User declined — offer alternatives
            String choice = prompt(reader,
                    "  [r]etry with more context, [w]izard, [q]uit? ");
            if (choice != null && choice.startsWith("r")) {
                String extra = prompt(reader, "  Additional context: ");
                String retryPrompt = buildAgentPrompt(projectName,
                        description + "\n\nAdditional context from user: " + extra, available.keySet());
                String retryOutput = invokeAgent(binary, agentName, retryPrompt);
                String retryJson = retryOutput != null ? extractJsonBlock(retryOutput) : null;
                if (retryJson != null) {
                    try {
                        InitProjectWizard.WizardResult retryResult = parseAgentJson(retryJson, projectName, outputDir);
                        if (confirmRecommendation(reader, retryResult, agentName, extractReasoning(retryJson))) {
                            return retryResult;
                        }
                    } catch (Exception ignored) {}
                }
                System.out.println(YELLOW + "  Retry did not produce a valid config." + RESET);
            }

            if (choice != null && choice.startsWith("w")) {
                System.out.println("  Launching interactive wizard...");
                return InitProjectWizard.run();
            }

            return null;
        }
    }

    // ── Agent detection ──────────────────────────────────────────────────────

    private static Map<String, String> detectAvailableAgents() {
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : ai.kompile.core.agent.CliAgentRegistry.commandNames()) {
            String path = SubprocessAgentRunner.resolveAgentBinary(name);
            if (path != null) {
                found.put(name, path);
            }
        }
        return found;
    }

    private static String chooseAgent(LineReader reader, Map<String, String> available, String preferred) {
        if (preferred != null && !preferred.equals("auto")) {
            String resolved = SubprocessAgentRunner.resolveAgentBinary(preferred);
            if (resolved != null) {
                return preferred.toLowerCase();
            }
            System.out.println(YELLOW + "  Agent '" + preferred + "' not found on PATH." + RESET);
            if (available.isEmpty()) {
                System.out.println(RED + "  No agents available. Use --wizard instead." + RESET);
                return null;
            }
        }

        if (available.size() == 1) {
            String agent = available.keySet().iterator().next();
            System.out.println("  Using " + BOLD + agent + RESET + " (only agent available)");
            return agent;
        }

        // Default to claude if available
        String defaultAgent = available.containsKey("claude") ? "claude"
                : available.keySet().iterator().next();

        String agentNames = String.join(", ", available.keySet());
        String input = prompt(reader,
                "  Agent to use [" + defaultAgent + "] (" + agentNames + "): ");
        if (input == null || input.isBlank()) return defaultAgent;

        String chosen = input.trim().toLowerCase();
        if (available.containsKey(chosen)) return chosen;

        System.out.println(YELLOW + "  Unknown agent '" + chosen + "', using " + defaultAgent + RESET);
        return defaultAgent;
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    static String buildAgentPrompt(String projectName, String description, Set<String> detectedAgents) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a kompile project configuration assistant. ");
        sb.append("The user is initializing a new kompile project.\n\n");

        sb.append("USER'S PROJECT DESCRIPTION:\n");
        sb.append(description).append("\n\n");
        sb.append("PROJECT NAME: ").append(projectName).append("\n\n");

        // Detected agents context
        if (detectedAgents != null && !detectedAgents.isEmpty()) {
            sb.append("DETECTED CLI AGENTS ON THIS MACHINE: ");
            sb.append(String.join(", ", detectedAgents));
            sb.append("\n(If the user's use case fits, prefer CLI_AGENT_RAG preset since they have a local agent)\n\n");
        }

        sb.append("Your task: analyze the description and output the optimal kompile configuration as JSON.\n\n");

        // Presets
        sb.append("---AVAILABLE PRESETS---\n");
        for (BuildPreset preset : BuildPreset.values()) {
            sb.append(preset.name()).append(": ").append(preset.getDescription());
            sb.append(" [").append(preset.getBackendAffinity()).append("]");
            sb.append("\n  Default modules: ");
            sb.append(String.join(", ", preset.getDefaultModules()));
            sb.append("\n");
        }
        sb.append("\n");

        // Module catalog by category
        sb.append("---AVAILABLE MODULES---\n");
        for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
            List<ModuleCatalog.ModuleEntry> modules = ModuleCatalog.getByCategory(cat);
            if (modules.isEmpty()) continue;
            sb.append("[").append(cat.name()).append("]\n");
            for (ModuleCatalog.ModuleEntry m : modules) {
                sb.append("  ").append(m.getId()).append(" — ").append(m.getDescription());
                if (m.isBundled()) sb.append(" (bundled)");
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Backend options
        sb.append("---BACKEND OPTIONS---\n");
        sb.append("nd4j-cuda-12.9: NVIDIA CUDA 12.9 GPU backend (fast embeddings, requires CUDA)\n");
        sb.append("nd4j-native: CPU backend (works everywhere, no GPU needed)\n");
        sb.append("If user has a GPU, prefer nd4j-cuda-12.9. Otherwise nd4j-native.\n\n");

        // Decision guide
        sb.append("---DECISION GUIDE---\n");
        sb.append("Knowledge/document workflows (RAG, Q&A, knowledge bases, search):\n");
        sb.append("  - CLI_AGENT_RAG if user has claude/codex installed (no API key needed)\n");
        sb.append("  - HOSTED_LLM_RAG if user wants OpenAI/Anthropic/Gemini API\n");
        sb.append("  - SAMEDIFF_RAG for fully local with no LLM (embedding + retrieval only)\n");
        sb.append("  - LITE for minimal footprint\n");
        sb.append("Coding/analysis workflows:\n");
        sb.append("  - CLI_AGENT_RAG + add code-indexer, react-agent\n");
        sb.append("Data processing pipelines:\n");
        sb.append("  - PIPELINE preset + pipeline-step-* modules\n");
        sb.append("Full-featured / everything enabled:\n");
        sb.append("  - FULL preset (all modules, all backends)\n\n");

        // Module selection tips
        sb.append("---MODULE TIPS---\n");
        sb.append("For PDFs: include loader-pdf-extended. For scanned/image PDFs: add ocr-core, ocr-models, ocr-integration.\n");
        sb.append("For web pages: add loader-web, crawler-core.\n");
        sb.append("For email: add loader-email-imap or source-email.\n");
        sb.append("For Office docs: add loader-microsoft.\n");
        sb.append("For Slack/Discord/Confluence: add relevant source-* or loader-* module.\n");
        sb.append("For entity relationships: add knowledge-graph, graph-neo4j.\n");
        sb.append("For code search: add code-indexer.\n");
        sb.append("For safety/guardrails: add guardrails.\n");
        sb.append("For query improvement: add query-transformer.\n");
        sb.append("For evaluation: add evaluation.\n\n");

        // Output format
        sb.append("---OUTPUT FORMAT---\n");
        sb.append("After your analysis, output EXACTLY this block (do not omit the delimiters):\n\n");
        sb.append(JSON_START_SENTINEL).append("\n");
        sb.append("{\n");
        sb.append("  \"preset\": \"<PRESET_NAME>\",\n");
        sb.append("  \"addModules\": [\"module-id-1\", \"module-id-2\"],\n");
        sb.append("  \"removeModules\": [],\n");
        sb.append("  \"backend\": \"nd4j-cuda-12.9 or nd4j-native\",\n");
        sb.append("  \"serverPort\": 8080,\n");
        sb.append("  \"reasoning\": \"1-2 sentence explanation\"\n");
        sb.append("}\n");
        sb.append(JSON_END_SENTINEL).append("\n\n");

        sb.append("Rules:\n");
        sb.append("- Start from the preset's default modules (don't list them in addModules)\n");
        sb.append("- addModules: modules to ADD beyond the preset default\n");
        sb.append("- removeModules: modules to REMOVE from the preset default (rarely needed)\n");
        sb.append("- Only use module IDs from the catalog above\n");
        sb.append("- Keep it focused — don't add every module, pick what fits the use case\n");
        sb.append("- Output ONLY the JSON block between the delimiters, with brief reasoning\n");

        return sb.toString();
    }

    // ── Agent invocation ─────────────────────────────────────────────────────

    private static String invokeAgent(String binary, String agentName, String agentPrompt) {
        List<String> cmd = buildOneShotCommand(binary, agentName, agentPrompt);
        try {
            return runProcessCaptureOutput(cmd);
        } catch (Exception e) {
            System.out.println(RED + "  Agent process failed: " + e.getMessage() + RESET);
            return null;
        }
    }

    private static List<String> buildOneShotCommand(String binary, String agentName, String prompt) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        String name = agentName.toLowerCase();
        if (name.contains("claude")) {
            cmd.add("-p");
            cmd.add(prompt);
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
        } else if (name.contains("codex")) {
            cmd.add("exec");
            cmd.add("--json");
            cmd.add(prompt);
        } else if (name.contains("gemini")) {
            cmd.add("-p");
            cmd.add(prompt);
            cmd.add("-o");
            cmd.add("stream-json");
        } else if (name.contains("qwen")) {
            cmd.add("-o");
            cmd.add("stream-json");
            cmd.add(prompt);
        } else if (name.contains("opencode")) {
            cmd.add("run");
            cmd.add("--format");
            cmd.add("json");
            cmd.add(prompt);
        } else {
            cmd.add("-p");
            cmd.add(prompt);
        }
        AgentFlagOverrides.addPermissionBypassFlags(cmd, agentName, true);
        return cmd;
    }

    private static String runProcessCaptureOutput(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        // Inherit essential env vars
        Map<String, String> env = pb.environment();
        for (String key : new String[]{"PATH", "HOME", "USER", "SHELL", "LANG",
                "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY", "JAVA_HOME"}) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Print progress dots for long-running agents
                if (output.length() % 2000 == 0) {
                    System.out.print(".");
                    System.out.flush();
                }
            }
        }

        boolean finished = process.waitFor(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Agent timed out after " + AGENT_TIMEOUT_SECONDS + " seconds");
        }

        System.out.println(); // end progress dots line
        return output.toString();
    }

    // ── JSON extraction and parsing ──────────────────────────────────────────

    static String extractJsonBlock(String rawOutput) {
        // Strategy 1: Parse stream-json events first (most reliable — Jackson unescapes for us)
        for (String line : rawOutput.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String text = null;

                // Claude stream-json: {"type":"result","result":"..."}
                if (node.has("type") && "result".equals(node.get("type").asText())) {
                    text = node.has("result") ? node.get("result").asText() : "";
                }
                // Claude stream-json: {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}
                else if (node.has("type") && "assistant".equals(node.get("type").asText())
                        && node.has("message")) {
                    JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode c : content) {
                            if ("text".equals(c.path("type").asText())) {
                                text = c.get("text").asText();
                                break;
                            }
                        }
                    }
                }

                if (text != null) {
                    String extracted = extractFromText(text);
                    if (extracted != null) return extracted;
                }
            } catch (Exception ignored) {}
        }

        // Strategy 2: Try sentinel-delimited extraction from raw output
        // (works for non-stream-json agents like codex, gemini raw mode)
        String extracted = extractFromText(rawOutput);
        if (extracted != null) return extracted;

        // Strategy 3: The raw output may have JSON-escaped sentinels from stream-json
        // (e.g., \\n instead of real newlines). Unescape and try again.
        String unescaped = unescapeJsonString(rawOutput);
        if (!unescaped.equals(rawOutput)) {
            extracted = extractFromText(unescaped);
            if (extracted != null) return extracted;
        }

        // Strategy 4: find any JSON object with "preset" key anywhere
        if (rawOutput.contains("\"preset\"") || rawOutput.contains("\\\"preset\\\"")) {
            String candidate = extractFirstJsonObject(unescaped);
            if (candidate != null) return candidate;
            candidate = extractFirstJsonObject(rawOutput);
            if (candidate != null) return candidate;
        }

        return null;
    }

    /**
     * Extract JSON from text that contains our sentinel delimiters,
     * or fall back to finding a JSON object with "preset" key.
     */
    private static String extractFromText(String text) {
        int start = text.indexOf(JSON_START_SENTINEL);
        int end = text.indexOf(JSON_END_SENTINEL);
        if (start >= 0 && end > start) {
            String block = text.substring(start + JSON_START_SENTINEL.length(), end).trim();
            // Validate it's parseable JSON
            try {
                MAPPER.readTree(block);
                return block;
            } catch (Exception e) {
                // Might still be escaped — try unescaping
                String unesc = unescapeJsonString(block);
                try {
                    MAPPER.readTree(unesc);
                    return unesc;
                } catch (Exception ignored) {}
            }
        }
        // No sentinels — try finding a preset JSON object
        if (text.contains("\"preset\"")) {
            return extractFirstJsonObject(text);
        }
        return null;
    }

    /**
     * Unescape JSON string escape sequences: \\n → \n, \\\" → \", \\\\ → \\, \\t → \t
     */
    private static String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String extractFirstJsonObject(String text) {
        int braceStart = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '{' && braceStart < 0) {
                // Check if this looks like our config JSON
                int presetIdx = text.indexOf("\"preset\"", i);
                if (presetIdx >= 0 && presetIdx < text.indexOf('}', i)) {
                    braceStart = i;
                }
            }
            if (braceStart >= 0 && text.charAt(i) == '}') {
                String candidate = text.substring(braceStart, i + 1);
                try {
                    JsonNode node = MAPPER.readTree(candidate);
                    if (node.has("preset")) return candidate;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    static InitProjectWizard.WizardResult parseAgentJson(
            String json, String projectName, File outputDir) throws Exception {

        JsonNode root = MAPPER.readTree(json);

        // Parse preset
        String presetName = root.has("preset") ? root.get("preset").asText() : "HOSTED_LLM_RAG";
        BuildPreset preset;
        try {
            preset = BuildPreset.valueOf(presetName.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            System.out.println(YELLOW + "  Unknown preset '" + presetName + "', defaulting to HOSTED_LLM_RAG" + RESET);
            preset = BuildPreset.HOSTED_LLM_RAG;
        }

        // Start from preset defaults
        Set<String> moduleIds = new LinkedHashSet<>(preset.getDefaultModules());
        Set<String> validIds = ModuleCatalog.allIds();

        // Add modules
        if (root.has("addModules") && root.get("addModules").isArray()) {
            for (JsonNode m : root.get("addModules")) {
                String id = m.asText();
                if (validIds.contains(id)) {
                    moduleIds.add(id);
                } else {
                    System.out.println(YELLOW + "  Skipping unknown module: " + id + RESET);
                }
            }
        }

        // Remove modules
        if (root.has("removeModules") && root.get("removeModules").isArray()) {
            for (JsonNode m : root.get("removeModules")) {
                moduleIds.remove(m.asText());
            }
        }

        // Backend
        String backend = root.has("backend") ? root.get("backend").asText() : "nd4j-native";
        List<String> backends = List.of(backend);

        // Port
        int port = root.has("serverPort") ? root.get("serverPort").asInt(8080) : 8080;

        // Auto-detect platform
        String javacppPlatform = detectPlatform();

        return new InitProjectWizard.WizardResult(
                projectName,
                outputDir,
                preset,
                moduleIds,
                port,
                javacppPlatform,
                false,  // startAfterBuild
                false,  // noBuild
                backends,
                null,   // javacppExtension
                projectName + " powered by Kompile",  // appTitle
                null, null, null,  // database
                true,   // enableSchemaInit
                null, null, null,  // API keys
                List.of("en"),     // supportedLanguages
                null,   // mavenHome
                List.of(),  // dataSources
                3,      // crawlDepth
                false,  // crawlMultimodal
                false   // crawlGraph
        );
    }

    private static String extractReasoning(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return root.has("reasoning") ? root.get("reasoning").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Confirmation UX ──────────────────────────────────────────────────────

    private static boolean confirmRecommendation(
            LineReader reader, InitProjectWizard.WizardResult result,
            String agentName, String reasoning) {

        System.out.println();
        System.out.println(BOLD + "  Recommendation" + RESET + DIM + " (" + agentName + ")" + RESET + ":");
        System.out.println();

        // Preset
        System.out.println("    Preset:   " + CYAN + (result.preset != null ? result.preset.name() : "custom") + RESET
                + DIM + " — " + (result.preset != null ? result.preset.getDescription() : "") + RESET);

        // Modules by category (compact)
        Map<ModuleCatalog.Category, List<String>> byCategory = new LinkedHashMap<>();
        for (String id : result.moduleIds) {
            ModuleCatalog.ModuleEntry entry = ModuleCatalog.get(id);
            if (entry != null) {
                byCategory.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(id);
            }
        }
        System.out.println("    Modules:  " + WHITE + result.moduleIds.size() + " modules" + RESET);
        for (Map.Entry<ModuleCatalog.Category, List<String>> e : byCategory.entrySet()) {
            System.out.println("      " + DIM + e.getKey().name() + ": " + RESET
                    + String.join(", ", e.getValue()));
        }

        // Highlight what was added beyond the preset
        if (result.preset != null) {
            Set<String> added = new LinkedHashSet<>(result.moduleIds);
            added.removeAll(result.preset.getDefaultModules());
            Set<String> removed = new LinkedHashSet<>(result.preset.getDefaultModules());
            removed.removeAll(result.moduleIds);
            if (!added.isEmpty()) {
                System.out.println("    Added:    " + GREEN + String.join(", ", added) + RESET);
            }
            if (!removed.isEmpty()) {
                System.out.println("    Removed:  " + YELLOW + String.join(", ", removed) + RESET);
            }
        }

        // Backend
        String backendDisplay = result.backends != null && !result.backends.isEmpty()
                ? result.backends.get(0) : "nd4j-native";
        System.out.println("    Backend:  " + backendDisplay);
        System.out.println("    Port:     " + result.serverPort);

        if (reasoning != null && !reasoning.isBlank()) {
            System.out.println();
            System.out.println("    " + DIM + "Reasoning: " + reasoning + RESET);
        }

        System.out.println();
        String confirm = prompt(reader, "  Create this project? [Y/n]: ");
        return confirm == null || confirm.isBlank() || confirm.toLowerCase().startsWith("y");
    }

    // ── Fallback ─────────────────────────────────────────────────────────────

    private static InitProjectWizard.WizardResult offerFallback(LineReader reader) {
        String choice = prompt(reader, "  Launch interactive wizard instead? [Y/n]: ");
        if (choice == null || choice.isBlank() || choice.toLowerCase().startsWith("y")) {
            try {
                return InitProjectWizard.run();
            } catch (Exception e) {
                System.out.println(RED + "  Wizard failed: " + e.getMessage() + RESET);
            }
        }
        return null;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static String prompt(LineReader reader, String message) {
        try {
            return reader.readLine(message);
        } catch (Exception e) {
            return null;
        }
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac")) {
            return (arch.contains("aarch64") || arch.contains("arm")) ? "macosx-arm64" : "macosx-x86_64";
        } else if (os.contains("win")) {
            return arch.contains("aarch64") ? "windows-arm64" : "windows-x86_64";
        } else {
            if (arch.contains("aarch64") || arch.contains("arm64")) return "linux-arm64";
            if (arch.contains("ppc64")) return "linux-ppc64le";
            return "linux-x86_64";
        }
    }
}
