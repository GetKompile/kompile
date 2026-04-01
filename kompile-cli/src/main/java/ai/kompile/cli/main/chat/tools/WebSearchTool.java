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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web search tool that provides search capability to the agent chat system.
 * Uses multiple search strategies with fallback:
 * 1. Brave Search API (if BRAVE_API_KEY env var is set)
 * 2. DuckDuckGo HTML search (public, no API key required)
 * 3. Google search via curl (fallback)
 */
public class WebSearchTool implements CliTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RESULTS = 10;
    private static final int DEFAULT_RESULTS = 5;
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Override
    public String id() { return "websearch"; }

    @Override
    public String description() {
        return "Search the web for information using a query string. Returns a list of results " +
                "with titles, URLs, and snippets. Useful for finding documentation, looking up " +
                "error messages, researching libraries, or getting current information. " +
                "Set BRAVE_API_KEY environment variable for higher quality results via Brave Search API.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "The search query");

        ObjectNode count = props.putObject("count");
        count.put("type", "integer");
        count.put("description", "Number of results to return (default 5, max 10)");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String permissionKey() { return "websearch"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Web search");

        String query = params.path("query").asText("");
        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }

        int count = params.path("count").asInt(DEFAULT_RESULTS);
        count = Math.max(1, Math.min(count, MAX_RESULTS));

        // Try Brave Search API first if key is available
        String braveApiKey = System.getenv("BRAVE_API_KEY");
        if (braveApiKey != null && !braveApiKey.isEmpty()) {
            try {
                List<SearchResult> results = searchBrave(query, count, braveApiKey);
                if (!results.isEmpty()) {
                    return formatResults(query, results, "Brave Search");
                }
            } catch (Exception e) {
                // Fall through to DuckDuckGo
            }
        }

        // Try DuckDuckGo HTML search
        try {
            List<SearchResult> results = searchDuckDuckGo(query, count);
            if (!results.isEmpty()) {
                return formatResults(query, results, "DuckDuckGo");
            }
        } catch (Exception e) {
            // Fall through to Google via curl
        }

        // Fallback: Google search via curl
        try {
            List<SearchResult> results = searchGoogleViaCurl(query, count);
            if (!results.isEmpty()) {
                return formatResults(query, results, "Google");
            }
        } catch (Exception e) {
            return ToolResult.error("All search strategies failed. Last error: " + e.getMessage());
        }

        return ToolResult.error("No search results found for: " + query);
    }

    /**
     * Search using Brave Search API.
     */
    private List<SearchResult> searchBrave(String query, int count, String apiKey) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.search.brave.com/res/v1/web/search?q=" + encoded + "&count=" + count);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Brave API returned HTTP " + response.statusCode());
        }

        List<SearchResult> results = new ArrayList<>();
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(response.body());
        JsonNode webResults = root.path("web").path("results");

        if (webResults.isArray()) {
            for (int i = 0; i < webResults.size() && results.size() < count; i++) {
                JsonNode item = webResults.get(i);
                String title = item.path("title").asText("");
                String url = item.path("url").asText("");
                String snippet = item.path("description").asText("");
                if (!title.isEmpty() && !url.isEmpty()) {
                    results.add(new SearchResult(title, url, cleanHtml(snippet)));
                }
            }
        }

        return results;
    }

    /**
     * Search using DuckDuckGo HTML endpoint (no API key required).
     */
    private List<SearchResult> searchDuckDuckGo(String query, int count) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://html.duckduckgo.com/html/?q=" + encoded);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("DuckDuckGo returned HTTP " + response.statusCode());
        }

        return parseDuckDuckGoHtml(response.body(), count);
    }

    /**
     * Parse DuckDuckGo HTML search results.
     * Results are contained in div.result elements with:
     * - a.result__a for title and URL
     * - a.result__snippet for the snippet text
     */
    private List<SearchResult> parseDuckDuckGoHtml(String html, int count) {
        List<SearchResult> results = new ArrayList<>();

        // Pattern to match result blocks - each result has a result__a link and result__snippet
        Pattern resultLinkPattern = Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern snippetPattern = Pattern.compile(
                "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        // Also try td.result__snippet for some result layouts
        Pattern snippetTdPattern = Pattern.compile(
                "<td[^>]*class=\"result__snippet\"[^>]*>(.*?)</td>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher linkMatcher = resultLinkPattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);
        Matcher snippetTdMatcher = snippetTdPattern.matcher(html);

        // Collect all snippets (from both patterns) in order
        List<String> snippets = new ArrayList<>();
        List<Integer> snippetPositions = new ArrayList<>();

        while (snippetMatcher.find()) {
            snippets.add(cleanHtml(snippetMatcher.group(1)));
            snippetPositions.add(snippetMatcher.start());
        }
        while (snippetTdMatcher.find()) {
            snippets.add(cleanHtml(snippetTdMatcher.group(1)));
            snippetPositions.add(snippetTdMatcher.start());
        }

        int snippetIdx = 0;
        while (linkMatcher.find() && results.size() < count) {
            String url = linkMatcher.group(1);
            String title = cleanHtml(linkMatcher.group(2));

            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }

            // Resolve DuckDuckGo redirect URLs
            url = resolveDdgUrl(url);

            // Skip DuckDuckGo internal links
            if (url.contains("duckduckgo.com") && !url.contains("duckduckgo.com/l/")) {
                continue;
            }

            // Find the nearest snippet after this link
            String snippet = "";
            int linkPos = linkMatcher.start();
            for (int i = snippetIdx; i < snippetPositions.size(); i++) {
                if (snippetPositions.get(i) > linkPos) {
                    snippet = snippets.get(i);
                    snippetIdx = i + 1;
                    break;
                }
            }

            results.add(new SearchResult(title, url, snippet));
        }

        return results;
    }

    /**
     * Resolve DuckDuckGo redirect URLs to the actual destination.
     */
    private String resolveDdgUrl(String url) {
        // DuckDuckGo uses redirect links like //duckduckgo.com/l/?uddg=<encoded_url>&...
        if (url.contains("duckduckgo.com/l/")) {
            Pattern uddgPattern = Pattern.compile("[?&]uddg=([^&]+)");
            Matcher m = uddgPattern.matcher(url);
            if (m.find()) {
                try {
                    return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // Return original URL if decode fails
                }
            }
        }
        // Handle protocol-relative URLs
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }

    /**
     * Fallback: Search Google by shelling out to curl and parsing the HTML.
     */
    private List<SearchResult> searchGoogleViaCurl(String query, int count) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.google.com/search?q=" + encoded + "&num=" + count;

        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sL",
                "-H", "User-Agent: " + USER_AGENT,
                "-H", "Accept: text/html",
                "-H", "Accept-Language: en-US,en;q=0.5",
                "--max-time", String.valueOf(TIMEOUT_SECONDS),
                url
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor(TIMEOUT_SECONDS + 5, java.util.concurrent.TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            throw new RuntimeException("curl exited with code " + process.exitValue());
        }

        return parseGoogleHtml(output.toString(), count);
    }

    /**
     * Parse Google search results HTML.
     * Google results typically have <h3> tags for titles and <a href="/url?q=..."> for links.
     */
    private List<SearchResult> parseGoogleHtml(String html, int count) {
        List<SearchResult> results = new ArrayList<>();

        // Google wraps results in <a href="/url?q=ACTUAL_URL&..."><h3>Title</h3></a>
        Pattern resultPattern = Pattern.compile(
                "<a[^>]*href=\"/url\\?q=([^&\"]+)[^\"]*\"[^>]*>.*?<h3[^>]*>(.*?)</h3>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher matcher = resultPattern.matcher(html);
        while (matcher.find() && results.size() < count) {
            String url;
            try {
                url = java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                url = matcher.group(1);
            }
            String title = cleanHtml(matcher.group(2));

            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }

            // Try to find a snippet near this result
            String snippet = extractGoogleSnippet(html, matcher.end());

            results.add(new SearchResult(title, url, snippet));
        }

        return results;
    }

    /**
     * Extract a snippet from Google HTML near the given position.
     */
    private String extractGoogleSnippet(String html, int startPos) {
        // Look for text in <span> tags near the result (Google puts snippets in spans)
        int searchEnd = Math.min(startPos + 2000, html.length());
        String region = html.substring(startPos, searchEnd);

        // Google often uses <span class="...">snippet text</span> patterns after the title
        Pattern spanPattern = Pattern.compile(
                "<span[^>]*>((?:(?!</span>).){40,300})</span>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = spanPattern.matcher(region);
        if (m.find()) {
            String snippet = cleanHtml(m.group(1));
            if (snippet.length() > 30 && !snippet.contains("<") && !snippet.contains("{")) {
                return snippet;
            }
        }

        return "";
    }

    /**
     * Strip HTML tags and decode common entities.
     */
    private String cleanHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Remove HTML tags
        text = text.replaceAll("<[^>]+>", "");
        // Decode common entities
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&#39;", "'");
        text = text.replace("&nbsp;", " ");
        text = text.replace("&#x27;", "'");
        text = text.replace("&#x2F;", "/");
        // Clean up whitespace
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /**
     * Format search results into a readable string for the LLM.
     */
    private ToolResult formatResults(String query, List<SearchResult> results, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query).append("\n");
        sb.append("Source: ").append(source).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append("\n");
            sb.append("   URL: ").append(r.url).append("\n");
            if (!r.snippet.isEmpty()) {
                sb.append("   ").append(r.snippet).append("\n");
            }
            sb.append("\n");
        }

        return ToolResult.success("websearch: " + query,
                sb.toString().trim(),
                Map.of("resultCount", results.size(), "source", source));
    }

    /**
     * Internal representation of a single search result.
     */
    private static class SearchResult {
        final String title;
        final String url;
        final String snippet;

        SearchResult(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
