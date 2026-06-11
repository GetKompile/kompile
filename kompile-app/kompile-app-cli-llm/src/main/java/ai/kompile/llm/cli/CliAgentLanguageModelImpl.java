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

package ai.kompile.llm.cli;

import ai.kompile.core.llm.LanguageModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LanguageModel implementation backed by a coding CLI agent (Claude Code, Codex, Gemini CLI).
 * <p>
 * Spawns the configured CLI as a subprocess in non-interactive (print) mode,
 * passes the RAG context + user query as a prompt, and captures the response.
 * This allows CLI-based coding agents to serve as the language model for RAG,
 * chat, and other services without requiring an API key.
 * </p>
 * <p>
 * Configuration is read from {@link CliAgentLlmConfigService} at runtime
 * (persisted to ~/.kompile/config/cli-llm-config.json, managed via REST API).
 * </p>
 */
@Service("cliAgentLanguageModel")
public class CliAgentLanguageModelImpl implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLanguageModelImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CliAgentLlmConfigService configService;

    public CliAgentLanguageModelImpl(CliAgentLlmConfigService configService) {
        this.configService = configService;
        log.info("CLI Agent LanguageModel initialized (config-driven, enabled={})", configService.isEnabled());
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        if (!configService.isEnabled()) {
            return "Error: CLI Agent LLM is not enabled. Enable it via the UI at /api/llm/cli-agent/config.";
        }

        log.debug("CLI Agent generating response for query: {}", userQuery);
        String prompt = buildRagPrompt(userQuery, context, false);
        return executeCliAgent(prompt);
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        if (!configService.isEnabled()) {
            String msg = "Error: CLI Agent LLM is not enabled. Enable it via the UI at /api/llm/cli-agent/config.";
            Generation generation = new Generation(new AssistantMessage(msg), null);
            return new ChatResponse(Collections.singletonList(generation));
        }

        log.debug("CLI Agent generating response with tool context for query: {}", userQuery);
        String prompt = buildRagPrompt(userQuery, context, true);
        String response = executeCliAgent(prompt);

        Generation generation = new Generation(new AssistantMessage(response), null);
        return new ChatResponse(Collections.singletonList(generation));
    }

    /**
     * Check whether this LanguageModel is active and usable.
     */
    public boolean isAvailable() {
        return configService.isEnabled() && configService.checkAvailability(configService.getActiveCommand());
    }

    /**
     * Build the RAG prompt combining context documents with the user query.
     */
    private String buildRagPrompt(String userQuery, List<String> context, boolean mentionTools) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a helpful AI assistant. Answer the user's query based on the provided context.\n");
        sb.append("If the context does not contain the answer, say that you don't know.\n");

        if (mentionTools) {
            sb.append("You have access to tools for RAG queries (rag_query), listing files (list_files), ");
            sb.append("and reading files (read_file). Use them if the context is insufficient.\n");
        }

        if (context != null && !context.isEmpty()) {
            sb.append("\nContext:\n");
            sb.append(context.stream().collect(Collectors.joining("\n---\n")));
            sb.append("\n\n");
        }

        sb.append("User query: ").append(userQuery);
        return sb.toString();
    }

    /**
     * Execute the CLI agent as a subprocess and capture its response.
     */
    private String executeCliAgent(String prompt) {
        String command = configService.getActiveCommand();

        if (!configService.checkAvailability(command)) {
            String msg = "CLI agent '" + command + "' is not available. Install it or change the active agent via the UI.";
            log.error(msg);
            return "Error: " + msg;
        }

        try {
            List<String> cmdLine = buildCommandLine(command, prompt);
            log.debug("Executing CLI agent: {}", String.join(" ", cmdLine.subList(0, Math.min(3, cmdLine.size()))) + " ...");

            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);

            String workDir = configService.getWorkingDirectory();
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new File(workDir));
            }

            var env = configService.getEnvironment();
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (configService.isClaudeAgent(command)) {
                        String parsed = parseClaudeStreamJson(line);
                        if (parsed != null) {
                            output.append(parsed);
                        }
                    } else {
                        output.append(line).append("\n");
                    }
                }
            }

            int timeout = configService.getTimeoutSeconds();
            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("CLI agent '{}' timed out after {} seconds", command, timeout);
                return "Error: CLI agent timed out after " + timeout + " seconds.";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("CLI agent '{}' exited with code {}", command, exitCode);
            }

            String result = output.toString().trim();
            log.debug("CLI agent '{}' response length: {} chars", command, result.length());
            return result;

        } catch (Exception e) {
            log.error("Error executing CLI agent '{}': {}", command, e.getMessage(), e);
            return "Error executing CLI agent: " + e.getMessage();
        }
    }

    /**
     * Build the full command line for the CLI agent subprocess.
     */
    private List<String> buildCommandLine(String command, String prompt) {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(command);

        // Resolve agent metadata (built-in or custom)
        CliAgentLlmConfigService.BuiltInAgent builtIn = configService.getBuiltInAgent(command);
        if (builtIn != null) {
            // Built-in agent: use known flags
            if (configService.isSkipPermissions() && builtIn.skipPermissionsFlag() != null) {
                cmdLine.add(builtIn.skipPermissionsFlag());
            }
            if (builtIn.defaultArgs() != null) {
                Collections.addAll(cmdLine, builtIn.defaultArgs());
            }
            if (builtIn.printFlag() != null) {
                cmdLine.add(builtIn.printFlag());
            }
        } else {
            // Custom agent: check config
            var customAgents = configService.getCustomAgents();
            CliAgentLlmConfigService.CustomAgentDef custom = customAgents.get(command);
            if (custom != null) {
                if (configService.isSkipPermissions() && custom.skipPermissionsFlag != null) {
                    cmdLine.add(custom.skipPermissionsFlag);
                }
                if (custom.outputFormatFlag != null) {
                    cmdLine.add(custom.outputFormatFlag);
                }
                if (custom.printFlag != null) {
                    cmdLine.add(custom.printFlag);
                }
            }
        }

        // User-configured additional args
        String[] additionalArgs = configService.getAdditionalArgs();
        if (additionalArgs != null) {
            Collections.addAll(cmdLine, additionalArgs);
        }

        // The prompt as the final argument
        cmdLine.add(prompt);

        return cmdLine;
    }

    /**
     * Parse Claude's stream-json output format to extract text content.
     * <p>
     * Claude Code stream-json emits lines like:
     * <pre>
     * {"type":"assistant","message":{"content":[{"type":"text","text":"Hello!"}],...},...}
     * {"type":"result","result":"Hello!",...}
     * </pre>
     */
    private String parseClaudeStreamJson(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            // assistant events: text content is at message.content[].text
            if ("assistant".equals(type)) {
                JsonNode contentArray = node.path("message").path("content");
                if (contentArray.isArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode block : contentArray) {
                        if ("text".equals(block.path("type").asText())) {
                            text.append(block.path("text").asText());
                        }
                    }
                    if (text.length() > 0) {
                        return text.toString();
                    }
                }
            }

            // result event: final answer is at result (string)
            if ("result".equals(type)) {
                JsonNode result = node.path("result");
                if (result.isTextual()) {
                    return result.asText();
                }
            }

        } catch (Exception e) {
            log.trace("Failed to parse stream-json line: {}", line);
        }

        return null;
    }
}
