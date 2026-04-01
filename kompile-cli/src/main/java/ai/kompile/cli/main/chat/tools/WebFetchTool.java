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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch content from a URL and return it as text/markdown.
 * Comparable to OpenCode's WebFetchTool.
 */
public class WebFetchTool implements CliTool {

    private static final int MAX_CONTENT_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String id() { return "webfetch"; }

    @Override
    public String description() {
        return "Fetch content from a URL and return it as text. Supports HTML (converted to " +
                "simplified text), plain text, and JSON. Maximum content size is 5MB with a " +
                "30-second timeout. Use this to retrieve documentation, API responses, or web content.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode url = props.putObject("url");
        url.put("type", "string");
        url.put("description", "The URL to fetch content from");

        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public String permissionKey() { return "webfetch"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Fetch URL");

        String urlStr = params.path("url").asText("");
        if (urlStr.isEmpty()) {
            return ToolResult.error("url is required");
        }

        // Upgrade http to https
        if (urlStr.startsWith("http://")) {
            urlStr = "https://" + urlStr.substring(7);
        }

        try {
            URI uri = URI.create(urlStr);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "kompile-cli/1.0")
                    .header("Accept", "text/html,application/json,text/plain,*/*")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return ToolResult.error("HTTP " + response.statusCode() + " from " + urlStr);
            }

            String body = response.body();
            if (body.length() > MAX_CONTENT_SIZE) {
                body = body.substring(0, MAX_CONTENT_SIZE) + "\n... (content truncated)";
            }

            String contentType = response.headers().firstValue("content-type").orElse("text/plain");

            // Simple HTML to text conversion
            if (contentType.contains("text/html")) {
                body = htmlToText(body);
            }

            return ToolResult.success(uri.getHost(),
                    body,
                    Map.of("statusCode", response.statusCode(),
                            "contentType", contentType,
                            "size", body.length()));

        } catch (Exception e) {
            return ToolResult.error("Error fetching URL: " + e.getMessage());
        }
    }

    /**
     * Simple HTML to text conversion - strips tags, decodes entities,
     * preserves structure.
     */
    private String htmlToText(String html) {
        // Remove script and style blocks
        html = html.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?si)<style[^>]*>.*?</style>", "");
        html = html.replaceAll("(?si)<nav[^>]*>.*?</nav>", "");

        // Convert headings to markdown
        html = html.replaceAll("(?i)<h1[^>]*>", "\n# ");
        html = html.replaceAll("(?i)</h1>", "\n");
        html = html.replaceAll("(?i)<h2[^>]*>", "\n## ");
        html = html.replaceAll("(?i)</h2>", "\n");
        html = html.replaceAll("(?i)<h3[^>]*>", "\n### ");
        html = html.replaceAll("(?i)</h3>", "\n");

        // Convert paragraph and line breaks
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)<p[^>]*>", "\n");
        html = html.replaceAll("(?i)</p>", "\n");
        html = html.replaceAll("(?i)<li[^>]*>", "\n- ");

        // Convert links
        Pattern linkPattern = Pattern.compile("(?i)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
        Matcher m = linkPattern.matcher(html);
        html = m.replaceAll("[$2]($1)");

        // Convert code blocks
        html = html.replaceAll("(?i)<pre[^>]*>", "\n```\n");
        html = html.replaceAll("(?i)</pre>", "\n```\n");
        html = html.replaceAll("(?i)<code[^>]*>", "`");
        html = html.replaceAll("(?i)</code>", "`");

        // Strip remaining tags
        html = html.replaceAll("<[^>]+>", "");

        // Decode common entities
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");
        html = html.replace("&quot;", "\"");
        html = html.replace("&#39;", "'");
        html = html.replace("&nbsp;", " ");

        // Clean up whitespace
        html = html.replaceAll("[ \\t]+", " ");
        html = html.replaceAll("\\n{3,}", "\n\n");

        return html.trim();
    }
}
