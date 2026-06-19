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

package ai.kompile.cli.main.chat.tools.custom;

import ai.kompile.cli.main.chat.render.ProcessManager;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges a {@link CustomToolDefinition} to the {@link CliTool} interface,
 * executing user-defined tools via bash commands or HTTP requests.
 *
 * <p>Template variables in the form {@code {{paramName}}} are substituted
 * with actual parameter values before execution. Shell metacharacters in
 * parameter values are escaped for bash execution to prevent injection.
 */
public class CustomToolBridge implements CliTool {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{(\\w+)}}");
    private static final int MAX_HTTP_RESPONSE = 1024 * 1024; // 1MB

    private final CustomToolDefinition definition;

    public CustomToolBridge(CustomToolDefinition definition) {
        this.definition = definition;
    }

    @Override
    public String id() {
        return "custom_" + definition.getName();
    }

    @Override
    public String description() {
        return definition.getDescription();
    }

    @Override
    public JsonNode parameterSchema() {
        return definition.getParameters();
    }

    @Override
    public String permissionKey() {
        return "custom_tool";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(),
                "Execute custom tool: " + definition.getName());

        ExecuteConfig exec = definition.getExecute();
        return switch (exec.getType()) {
            case "bash" -> executeBash(exec, params, context);
            case "http" -> executeHttp(exec, params);
            default -> ToolResult.error("Unknown execute type: " + exec.getType());
        };
    }

    private ToolResult executeBash(ExecuteConfig exec,
                                   JsonNode params, ToolContext context) {
        String command = substituteTemplateVars(exec.getCommand(), params, true);

        Path workDir = context.getWorkingDirectory();
        if (exec.getWorkingDir() != null && !exec.getWorkingDir().isBlank()) {
            workDir = workDir.resolve(exec.getWorkingDir()).normalize();
        }

        int timeoutMs = definition.getTimeoutSeconds() * 1000;

        ProcessManager.ProcessResult result = ProcessManager.execute(
                command, workDir, timeoutMs, context.getAbortSignal());

        String output = result.getOutput();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("exitCode", result.getExitCode());
        meta.put("durationMs", result.getDurationMs());
        meta.put("customTool", definition.getName());

        if (result.isTimedOut()) {
            return new ToolResult("timed out after " + definition.getTimeoutSeconds() + "s",
                    output, meta, true);
        }
        if (result.isAborted()) {
            return new ToolResult("aborted", output, meta, true);
        }
        if (result.getExitCode() != 0) {
            return new ToolResult("exit " + result.getExitCode(), output, meta, true);
        }

        return ToolResult.success(definition.getName(), output, meta);
    }

    private ToolResult executeHttp(ExecuteConfig exec, JsonNode params) {
        String url = substituteTemplateVars(exec.getUrl(), params, false);
        String method = exec.getMethod() != null ? exec.getMethod().toUpperCase() : "GET";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "kompile-cli/1.0")
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(Duration.ofSeconds(definition.getTimeoutSeconds()));

            // Add custom headers
            if (exec.getHeaders() != null && exec.getHeaders().isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = exec.getHeaders().fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String headerValue = substituteTemplateVars(
                            entry.getValue().asText(), params, false);
                    reqBuilder.header(entry.getKey(), headerValue);
                }
            }

            // Set method and body
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                String body = exec.getBody() != null
                        ? substituteTemplateVars(exec.getBody(), params, false)
                        : "";
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else if ("DELETE".equals(method)) {
                reqBuilder.DELETE();
            } else {
                reqBuilder.GET();
            }

            HttpResponse<String> response = client.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            if (body != null && body.length() > MAX_HTTP_RESPONSE) {
                body = body.substring(0, MAX_HTTP_RESPONSE) + "\n... (response truncated)";
            }

            Map<String, Object> meta = Map.of(
                    "statusCode", response.statusCode(),
                    "customTool", definition.getName()
            );

            if (response.statusCode() >= 400) {
                return new ToolResult("HTTP " + response.statusCode(),
                        body != null ? body : "", meta, true);
            }

            return ToolResult.success(definition.getName(), body != null ? body : "", meta);

        } catch (Exception e) {
            return ToolResult.error("HTTP request failed: " + e.getMessage());
        }
    }

    /**
     * Substitute {@code {{varName}}} template variables with parameter values.
     *
     * @param template     the template string
     * @param params       the tool call parameters
     * @param shellEscape  if true, escape shell metacharacters in values
     * @return the substituted string
     */
    static String substituteTemplateVars(String template, JsonNode params, boolean shellEscape) {
        if (template == null) return "";

        Matcher matcher = TEMPLATE_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            JsonNode value = params.path(varName);
            String replacement;
            if (value.isMissingNode() || value.isNull()) {
                replacement = "";
            } else {
                replacement = value.asText();
            }
            if (shellEscape) {
                replacement = escapeShell(replacement);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Escape shell metacharacters to prevent command injection.
     * Wraps the value in single quotes with proper escaping.
     */
    static String escapeShell(String value) {
        if (value == null || value.isEmpty()) return "''";
        // If value is safe (alphanumeric, hyphens, underscores, dots, slashes), no quoting needed
        if (value.matches("[a-zA-Z0-9._/:-]+")) return value;
        // Otherwise wrap in single quotes, escaping any embedded single quotes
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
