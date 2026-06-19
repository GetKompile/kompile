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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Browser automation tool for agent sessions. Supports multiple backends:
 * - CDP (Chrome DevTools Protocol) via HTTP — connects to a running Chrome/Chromium with --remote-debugging-port
 * - Subprocess — launches a headless browser command for one-shot operations
 *
 * Inspired by jcode's browser tool. Provides: open, screenshot, click, type, evaluate, get_content, status.
 */
public class BrowserTool implements CliTool {

    private static final int DEFAULT_CDP_PORT = 9222;
    private static final int HTTP_TIMEOUT_MS = 10_000;

    @Override
    public String id() { return "browser"; }

    @Override
    public String description() {
        return "Browser automation tool. Actions: "
             + "'status' checks if a browser is available, "
             + "'open' navigates to a URL, "
             + "'screenshot' captures a screenshot (saves to file), "
             + "'get_content' gets page text content or HTML, "
             + "'evaluate' runs JavaScript in the page, "
             + "'click' clicks a CSS selector, "
             + "'type' types text into a CSS selector, "
             + "'wait' waits for a CSS selector. "
             + "Requires Chrome/Chromium running with --remote-debugging-port=9222 or KOMPILE_CDP_PORT env var. "
             + "Start with: chromium --headless --remote-debugging-port=9222";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: status, open, screenshot, get_content, evaluate, click, type, wait");

        ObjectNode url = props.putObject("url");
        url.put("type", "string");
        url.put("description", "URL to navigate to (for open action)");

        ObjectNode selector = props.putObject("selector");
        selector.put("type", "string");
        selector.put("description", "CSS selector (for click, type, wait actions)");

        ObjectNode text = props.putObject("text");
        text.put("type", "string");
        text.put("description", "Text to type (for type action)");

        ObjectNode expression = props.putObject("expression");
        expression.put("type", "string");
        expression.put("description", "JavaScript expression (for evaluate action)");

        ObjectNode outputPath = props.putObject("output_path");
        outputPath.put("type", "string");
        outputPath.put("description", "Output file path (for screenshot action)");

        ObjectNode format = props.putObject("format");
        format.put("type", "string");
        format.put("description", "Content format: text or html (for get_content, default text)");

        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "integer");
        timeout.put("description", "Timeout in ms (for wait action, default 5000)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "browser"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Browser automation");

        String action = params.path("action").asText("");
        int cdpPort = Integer.parseInt(System.getenv().getOrDefault("KOMPILE_CDP_PORT",
            String.valueOf(DEFAULT_CDP_PORT)));

        switch (action) {
            case "status":
                return checkStatus(cdpPort);

            case "open": {
                String url = params.path("url").asText("");
                if (url.isEmpty()) return ToolResult.error("url is required for open action");
                return navigateTo(cdpPort, url);
            }

            case "screenshot": {
                String outputPath = params.path("output_path").asText(
                    System.getProperty("java.io.tmpdir") + "/kompile-screenshot-" + System.currentTimeMillis() + ".png");
                return takeScreenshot(cdpPort, outputPath);
            }

            case "get_content": {
                String format = params.path("format").asText("text");
                return getContent(cdpPort, format);
            }

            case "evaluate": {
                String expression = params.path("expression").asText("");
                if (expression.isEmpty()) return ToolResult.error("expression is required for evaluate action");
                return evaluateJs(cdpPort, expression);
            }

            case "click": {
                String selector = params.path("selector").asText("");
                if (selector.isEmpty()) return ToolResult.error("selector is required for click action");
                return evaluateJs(cdpPort,
                    "document.querySelector('" + escapeJs(selector) + "')?.click() ?? 'Element not found'");
            }

            case "type": {
                String selector = params.path("selector").asText("");
                String text = params.path("text").asText("");
                if (selector.isEmpty()) return ToolResult.error("selector is required for type action");
                return evaluateJs(cdpPort,
                    "(() => { const el = document.querySelector('" + escapeJs(selector) + "'); "
                    + "if (!el) return 'Element not found'; "
                    + "el.focus(); el.value = '" + escapeJs(text) + "'; "
                    + "el.dispatchEvent(new Event('input', {bubbles: true})); "
                    + "return 'typed'; })()");
            }

            case "wait": {
                String selector = params.path("selector").asText("");
                int timeout = params.path("timeout").asInt(5000);
                if (selector.isEmpty()) return ToolResult.error("selector is required for wait action");
                return evaluateJs(cdpPort,
                    "await new Promise((resolve, reject) => { "
                    + "const el = document.querySelector('" + escapeJs(selector) + "'); "
                    + "if (el) { resolve('found'); return; } "
                    + "const obs = new MutationObserver(() => { "
                    + "  if (document.querySelector('" + escapeJs(selector) + "')) { obs.disconnect(); resolve('found'); } "
                    + "}); "
                    + "obs.observe(document.body, {childList: true, subtree: true}); "
                    + "setTimeout(() => { obs.disconnect(); reject('timeout'); }, " + timeout + "); "
                    + "})");
            }

            default:
                return ToolResult.error("Unknown action: " + action
                    + ". Use: status, open, screenshot, get_content, evaluate, click, type, wait");
        }
    }

    // ── CDP Communication ─────────────────────────────────────────────────

    private ToolResult checkStatus(int port) {
        try {
            String response = cdpGet("http://localhost:" + port + "/json/version");
            return ToolResult.success("browser: connected",
                "Browser is available via CDP on port " + port + "\n" + response);
        } catch (Exception e) {
            return ToolResult.success("browser: not connected",
                "No browser detected on CDP port " + port + ". "
                + "Start Chrome/Chromium with:\n"
                + "  chromium --headless --remote-debugging-port=" + port + "\n"
                + "Or set KOMPILE_CDP_PORT env var for a different port.\n"
                + "Error: " + e.getMessage());
        }
    }

    private ToolResult navigateTo(int port, String url) {
        try {
            // Get first page target
            String targets = cdpGet("http://localhost:" + port + "/json");
            String targetId = extractFirstTargetId(targets);
            if (targetId == null) {
                // Create a new page
                cdpGet("http://localhost:" + port + "/json/new?" + url);
                return ToolResult.success("browser: opened", "Opened new tab: " + url);
            }
            // Navigate existing page via CDP command
            cdpSendCommand(port, targetId, "Page.navigate",
                "{\"url\":\"" + escapeJson(url) + "\"}");
            return ToolResult.success("browser: navigated", "Navigated to: " + url);
        } catch (Exception e) {
            return ToolResult.error("Navigation failed: " + e.getMessage());
        }
    }

    private ToolResult takeScreenshot(int port, String outputPath) {
        try {
            String targets = cdpGet("http://localhost:" + port + "/json");
            String targetId = extractFirstTargetId(targets);
            if (targetId == null) return ToolResult.error("No browser page open");

            String result = cdpSendCommand(port, targetId, "Page.captureScreenshot", "{}");
            // Extract base64 data from result
            String data = extractJsonValue(result, "data");
            if (data != null) {
                byte[] imageBytes = Base64.getDecoder().decode(data);
                Files.write(Paths.get(outputPath), imageBytes);
                return ToolResult.success("browser: screenshot",
                    "Screenshot saved to " + outputPath + " (" + imageBytes.length + " bytes)");
            }
            return ToolResult.error("Failed to capture screenshot");
        } catch (Exception e) {
            return ToolResult.error("Screenshot failed: " + e.getMessage());
        }
    }

    private ToolResult getContent(int port, String format) {
        String js = "text".equals(format)
            ? "document.body.innerText"
            : "document.documentElement.outerHTML";
        return evaluateJs(port, js);
    }

    private ToolResult evaluateJs(int port, String expression) {
        try {
            String targets = cdpGet("http://localhost:" + port + "/json");
            String targetId = extractFirstTargetId(targets);
            if (targetId == null) return ToolResult.error("No browser page open");

            String result = cdpSendCommand(port, targetId, "Runtime.evaluate",
                "{\"expression\":\"" + escapeJson(expression) + "\",\"returnByValue\":true}");

            // Extract the value from the result
            String value = extractNestedJsonValue(result, "result", "value");
            if (value != null) {
                return ToolResult.success("browser: evaluate", value);
            }
            // Check for exceptions
            String exDesc = extractNestedJsonValue(result, "exceptionDetails", "text");
            if (exDesc != null) {
                return ToolResult.error("JS Error: " + exDesc);
            }
            return ToolResult.success("browser: evaluate", result);
        } catch (Exception e) {
            return ToolResult.error("Evaluate failed: " + e.getMessage());
        }
    }

    // ── CDP HTTP helpers ──────────────────────────────────────────────────

    private String cdpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Send a CDP command via WebSocket is complex — use the HTTP JSON endpoints instead.
     * For commands requiring WebSocket, fall back to subprocess curl.
     */
    private String cdpSendCommand(int port, String targetId, String method, String paramsJson) throws IOException {
        // Use the /json/protocol endpoint pattern or fall back to a simple approach
        // For simplicity, we use a one-shot websocket via subprocess
        String wsUrl = "ws://localhost:" + port + "/devtools/page/" + targetId;
        String payload = "{\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";

        // Try websocat first, fall back to python
        try {
            ProcessBuilder pb = new ProcessBuilder("websocat", "-1", wsUrl);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().close();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            return output;
        } catch (Exception e) {
            // Fallback to python one-liner
            ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import asyncio, websockets, json, sys\n"
                + "async def send():\n"
                + "    async with websockets.connect('" + wsUrl + "') as ws:\n"
                + "        await ws.send('" + payload.replace("'", "\\'") + "')\n"
                + "        print(await ws.recv())\n"
                + "asyncio.run(send())\n");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try { proc.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return output;
        }
    }

    // ── JSON helpers (no external deps) ───────────────────────────────────

    private static String extractFirstTargetId(String jsonArray) {
        // Simple extraction from CDP /json response - find "id":"<value>"
        int idx = jsonArray.indexOf("\"id\":");
        if (idx < 0) return null;
        int start = jsonArray.indexOf("\"", idx + 5) + 1;
        int end = jsonArray.indexOf("\"", start);
        return start > 0 && end > start ? jsonArray.substring(start, end) : null;
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static String extractNestedJsonValue(String json, String outerKey, String innerKey) {
        int outerIdx = json.indexOf("\"" + outerKey + "\"");
        if (outerIdx < 0) return null;
        String sub = json.substring(outerIdx);
        return extractJsonValue(sub, innerKey);
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
