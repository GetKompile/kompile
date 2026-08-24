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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.app.services.diffindex.DiffIndexEntry;
import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read-only MCP bridge to the application's diff index.
 *
 * <p>The diff index mines edit/write/patch calls from CLI transcripts and stores
 * their old text, new text, and unified diff. This tool makes that history
 * queryable from the stdio MCP surface, where the Spring {@code @Tool}-based
 * diff tools are not otherwise present.
 */
public class DiffIndexTool implements CliTool {

    static final String API_ROOT = "/api/diff-index";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int SNIPPET_CHARS = 600;
    private static final Path LOCAL_INDEX_PATH =
            Path.of(".kompile", "agent-state", "diff-index");
    private static final Set<String> SORT_FIELDS = Set.of(
            "timestamp", "file_path", "project", "agent", "source",
            "lines_added", "lines_removed", "total_changes");
    private static final Set<String> SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> SCOPES = Set.of("project", "global");

    private final ObjectMapper objectMapper;
    private final BackendGateway backend;

    public DiffIndexTool(String baseUrl, ObjectMapper objectMapper) {
        this(objectMapper, baseUrl == null || baseUrl.isBlank()
                ? new LocalBackendGateway(objectMapper)
                : sharedBackend(baseUrl));
    }

    DiffIndexTool(ObjectMapper objectMapper, BackendGateway backend) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public String id() {
        return "diff_index";
    }

    @Override
    public String description() {
        return "Search historical file edits mined from Claude, Codex, OpenCode, Gemini, "
                + "and other coding-agent transcripts. Unlike grep (current file content) and "
                + "file_activity (path-level notifications), this queries old text, new text, "
                + "and unified diffs. Local mode uses the current directory project and stores its "
                + "index under that project; pass scope='global' only for cross-project history. "
                + "Actions: 'search' (content/path/project/agent/source/time "
                + "filters plus configurable sorting), 'get' (full edit by id), 'projects', 'agents', 'sessions', "
                + "'session' (all edits in one session), and 'stats'.";
    }

    @Override
    public String compactHint() {
        return "Historical edit search scoped to the current directory project by default; "
                + "use scope=global only for cross-project history. action=search supports filters plus sort_by/sort_dir; "
                + "action=get with id returns the full old/new/unified diff.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = prop(props, "action", "string",
                "Action: search, get, projects, agents, sessions, session, or stats");
        action.putArray("enum")
                .add("search").add("get").add("projects").add("agents")
                .add("sessions").add("session").add("stats");

        ObjectNode scope = prop(props, "scope", "string",
                "Local mode scope: project (default, current directory project) or global (cross-project user index)");
        scope.putArray("enum").add("project").add("global");

        prop(props, "query", "string",
                "search: case-insensitive substring in file path, old text, new text, or unified diff");
        prop(props, "file_path", "string",
                "search: file path substring or glob (for example src/**/*.java)");
        prop(props, "project", "string",
                "search: project directory path or project-name substring");
        prop(props, "agent", "string",
                "search: exact coding-agent name (for example codex or claude-code)");
        prop(props, "source", "string",
                "search: exact transcript source name");
        prop(props, "since", "string",
                "search: inclusive ISO timestamp/date lower bound");
        prop(props, "until", "string",
                "search: inclusive ISO timestamp/date upper bound");
        ObjectNode sortBy = prop(props, "sort_by", "string",
                "search: timestamp, file_path, project, agent, source, lines_added, lines_removed, or total_changes (default timestamp)");
        sortBy.putArray("enum")
                .add("timestamp").add("file_path").add("project").add("agent").add("source")
                .add("lines_added").add("lines_removed").add("total_changes");
        ObjectNode sortDir = prop(props, "sort_dir", "string",
                "search: asc or desc (default desc)");
        sortDir.putArray("enum").add("asc").add("desc");
        prop(props, "limit", "integer",
                "search: maximum entries, 1-100 (default 20)");
        prop(props, "include_content", "boolean",
                "search/session: include full old/new/unified diff content (default false; matching snippets are still shown)");
        prop(props, "id", "string",
                "get: diff entry id");
        prop(props, "session_id", "string",
                "session: transcript session id");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Query historical file edits");

        String action = params.path("action").asText("").trim().toLowerCase();
        String requestedScope = text(params, "scope");
        requestedScope = requestedScope == null ? "project" : requestedScope.toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(requestedScope)) {
            return ToolResult.error("scope must be 'project' or 'global'");
        }
        RequestScope scope = backend.isLocal()
                ? ("global".equals(requestedScope)
                    ? RequestScope.global()
                    : RequestScope.project(resolveProjectRoot(context.getWorkingDirectory())))
                : RequestScope.remote();
        return switch (action) {
            case "search" -> search(params, scope);
            case "get" -> getEntry(params, scope);
            case "projects" -> getJson(API_ROOT + "/projects", "diff_index: projects", "projects", scope);
            case "agents" -> getJson(API_ROOT + "/agents", "diff_index: agents", "agents", scope);
            case "sessions" -> getJson(API_ROOT + "/sessions", "diff_index: sessions", "sessions", scope);
            case "session" -> getSession(params, scope);
            case "stats" -> getJson(API_ROOT + "/stats", "diff_index: stats", "stats", scope);
            default -> ToolResult.error("Unknown action: '" + action
                    + "'. Use search, get, projects, agents, sessions, session, or stats.");
        };
    }

    private ToolResult search(JsonNode params, RequestScope scope) {
        int limit = params.path("limit").asInt(DEFAULT_LIMIT);
        if (limit < 1 || limit > MAX_LIMIT) {
            return ToolResult.error("limit must be between 1 and " + MAX_LIMIT);
        }
        String sortBy = sortBy(params);
        if (!SORT_FIELDS.contains(sortBy)) {
            return ToolResult.error("sort_by must be one of: " + SORT_FIELDS);
        }
        String sortDir = sortDir(params);
        if (!SORT_DIRECTIONS.contains(sortDir)) {
            return ToolResult.error("sort_dir must be 'asc' or 'desc'");
        }

        String path = buildSearchPath(params, limit);
        BackendResponse response = request(path, scope);
        if (response.error() != null) {
            return ToolResult.error(response.error());
        }

        try {
            JsonNode entries = objectMapper.readTree(response.body());
            return formatEntries("diff_index: search", entries, params, scope);
        } catch (Exception e) {
            return ToolResult.error("Invalid diff-index search response: " + e.getMessage());
        }
    }

    private ToolResult getEntry(JsonNode params, RequestScope scope) {
        String id = text(params, "id");
        if (id == null) {
            return ToolResult.error("id is required for get");
        }
        return getJson(API_ROOT + "/entries/" + encode(id), "diff_index: " + id, "get", scope);
    }

    private ToolResult getSession(JsonNode params, RequestScope scope) {
        String sessionId = text(params, "session_id");
        if (sessionId == null) {
            return ToolResult.error("session_id is required for session");
        }

        BackendResponse response = request(API_ROOT + "/sessions/" + encode(sessionId), scope);
        if (response.error() != null) {
            return ToolResult.error(response.error());
        }
        try {
            JsonNode entries = objectMapper.readTree(response.body());
            return formatEntries("diff_index: session " + sessionId, entries, params, scope);
        } catch (Exception e) {
            return ToolResult.error("Invalid diff-index session response: " + e.getMessage());
        }
    }

    private ToolResult getJson(String path, String title, String action, RequestScope scope) {
        BackendResponse response = request(path, scope);
        if (response.error() != null) {
            return ToolResult.error(response.error());
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            int count = json.isArray() ? json.size() : 1;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("action", action);
            metadata.put("count", count);
            scope.addMetadata(metadata);
            return ToolResult.success(title,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json), metadata);
        } catch (Exception e) {
            return ToolResult.error("Invalid diff-index response: " + e.getMessage());
        }
    }

    private BackendResponse request(String path, RequestScope scope) {
        if (!backend.isAvailable(path)) {
            return BackendResponse.error("The explicitly configured remote diff-index backend is unavailable. "
                    + "Remove --url to use the in-process stdio index, or restore that remote endpoint.");
        }

        try {
            BackendResponse response = backend.get(path, REQUEST_TIMEOUT, scope);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return BackendResponse.error("Diff-index request failed (HTTP "
                        + response.statusCode() + "): " + extractError(response.body()));
            }
            return response;
        } catch (ConnectException e) {
            return BackendResponse.error("Cannot connect to the diff-index backend: " + e.getMessage());
        } catch (HttpTimeoutException e) {
            return BackendResponse.error("Diff-index request timed out after 30 seconds.");
        } catch (Exception e) {
            return BackendResponse.error("Diff-index request failed: " + e.getMessage());
        }
    }

    String buildSearchPath(JsonNode params, int limit) {
        StringBuilder path = new StringBuilder(API_ROOT).append("/search");
        boolean[] first = {true};
        appendQuery(path, first, "agent", text(params, "agent"));
        appendQuery(path, first, "projectDirectory", text(params, "project"));
        appendQuery(path, first, "filePath", text(params, "file_path"));
        appendQuery(path, first, "contentQuery", text(params, "query"));
        appendQuery(path, first, "source", text(params, "source"));
        appendQuery(path, first, "since", text(params, "since"));
        appendQuery(path, first, "until", text(params, "until"));
        appendQuery(path, first, "sortBy", sortBy(params));
        appendQuery(path, first, "sortDir", sortDir(params));
        appendQuery(path, first, "limit", Integer.toString(limit));
        return path.toString();
    }

    private ToolResult formatEntries(String title, JsonNode entries, JsonNode params,
                                     RequestScope scope) {
        if (!entries.isArray()) {
            return ToolResult.error("Diff-index response was not an array of edit entries.");
        }

        String query = text(params, "query");
        boolean includeContent = params.path("include_content").asBoolean(false);
        StringBuilder out = new StringBuilder();

        if (entries.isEmpty()) {
            out.append("No indexed file edits matched the supplied filters.");
        } else {
            out.append("Found ").append(entries.size()).append(" indexed file edit(s)");
            if ("diff_index: search".equals(title)) {
                out.append(" (sorted by ").append(sortBy(params)).append(' ')
                        .append(sortDir(params)).append(')');
            }
            out.append(".\n");
            for (int i = 0; i < entries.size(); i++) {
                JsonNode entry = entries.get(i);
                out.append("\n## ").append(i + 1).append(". ")
                        .append(entry.path("filePath").asText("(unknown file)")).append("\n");
                appendDetail(out, "id", entry.path("id").asText(null));
                appendDetail(out, "project", entry.path("projectDirectory").asText(null));
                appendDetail(out, "agent", entry.path("agent").asText(null));
                appendDetail(out, "source", entry.path("source").asText(null));
                appendDetail(out, "session", entry.path("sessionId").asText(null));
                appendDetail(out, "timestamp", entry.path("timestamp").asText(null));
                appendDetail(out, "tool", entry.path("toolName").asText(null));
                appendDetail(out, "type", entry.path("diffType").asText(null));
                out.append("lines: +").append(entry.path("linesAdded").asInt(0))
                        .append(" / -").append(entry.path("linesRemoved").asInt(0)).append("\n");

                if (includeContent) {
                    appendContent(out, "old text", entry.path("oldString").asText(null));
                    appendContent(out, "new text", entry.path("newString").asText(null));
                    appendContent(out, "unified diff", entry.path("unifiedDiff").asText(null));
                } else if (query != null) {
                    String snippet = matchingSnippet(entry, query);
                    if (snippet != null) {
                        out.append("match:\n").append(snippet).append("\n");
                    }
                }
            }
            if (!includeContent) {
                out.append("\nUse action=get with an entry id for full old/new/unified diff content, ")
                        .append("or repeat with include_content=true.");
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", title.contains("session") ? "session" : "search");
        metadata.put("count", entries.size());
        if (query != null) {
            metadata.put("query", query);
        }
        if ("diff_index: search".equals(title)) {
            metadata.put("sort_by", sortBy(params));
            metadata.put("sort_dir", sortDir(params));
        }
        scope.addMetadata(metadata);
        return ToolResult.success(title, out.toString(), metadata);
    }

    private String matchingSnippet(JsonNode entry, String query) {
        String lowerQuery = query.toLowerCase();
        for (String field : new String[]{"unifiedDiff", "newString", "oldString"}) {
            String value = entry.path(field).asText(null);
            if (value == null) {
                continue;
            }
            int match = value.toLowerCase().indexOf(lowerQuery);
            if (match >= 0) {
                int radius = SNIPPET_CHARS / 2;
                int start = Math.max(0, match - radius);
                int end = Math.min(value.length(), match + query.length() + radius);
                return (start > 0 ? "…" : "") + value.substring(start, end)
                        + (end < value.length() ? "…" : "");
            }
        }
        return null;
    }

    private static void appendDetail(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append("\n");
        }
    }

    private static void appendContent(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(":\n").append(value).append("\n");
        }
    }

    private static ObjectNode prop(ObjectNode props, String name, String type, String description) {
        ObjectNode property = props.putObject(name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static String text(JsonNode params, String name) {
        String value = params.path(name).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sortBy(JsonNode params) {
        String value = text(params, "sort_by");
        return value == null ? "timestamp" : value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String sortDir(JsonNode params) {
        String value = text(params, "sort_dir");
        return value == null ? "desc" : value.toLowerCase(Locale.ROOT);
    }

    private static void appendQuery(StringBuilder path, boolean[] first, String name, String value) {
        if (value == null) {
            return;
        }
        path.append(first[0] ? '?' : '&')
                .append(name).append('=').append(encode(value));
        first[0] = false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Path resolveProjectRoot(Path workingDirectory) {
        Path working = (workingDirectory == null ? Path.of(".") : workingDirectory)
                .toAbsolutePath().normalize();
        return new KompileProjectStore().findProjectRoot(working).orElse(working);
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            String message = json.path("message").asText(null);
            if (message == null) {
                message = json.path("error").asText(null);
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to a bounded raw response.
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static BackendGateway sharedBackend(String baseUrl) {
        KompileBackendClient client = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isBlank()) {
            client.setBaseUrl(baseUrl);
        }
        return new BackendGateway() {
            @Override
            public boolean isAvailable(String path) {
                return client.isAvailable(path);
            }

            @Override
            public BackendResponse get(String path, Duration timeout, RequestScope scope) throws Exception {
                HttpResponse<String> response = client.get(path, timeout);
                return new BackendResponse(response.statusCode(), response.body(), null);
            }
        };
    }

    /** In-process adapter over the same persisted diff index used by the application UI. */
    static final class LocalBackendGateway implements BackendGateway {
        private final ObjectMapper mapper;
        private final Function<RequestScope, DiffIndexService> serviceFactory;
        private final Map<String, ServiceState> services = new ConcurrentHashMap<>();

        LocalBackendGateway(ObjectMapper mapper) {
            this(mapper, scope -> scope.isGlobal()
                    ? new DiffIndexService()
                    : new DiffIndexService(scope.projectRoot().resolve(LOCAL_INDEX_PATH)));
        }

        LocalBackendGateway(ObjectMapper mapper, DiffIndexService service) {
            this(mapper, ignored -> service);
        }

        private LocalBackendGateway(ObjectMapper mapper,
                                    Function<RequestScope, DiffIndexService> serviceFactory) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");
        }

        @Override
        public boolean isAvailable(String path) {
            return true;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public synchronized BackendResponse get(String path, Duration timeout, RequestScope scope) {
            try {
                ServiceState state = services.computeIfAbsent(scope.cacheKey(),
                        ignored -> new ServiceState(serviceFactory.apply(scope)));
                ensureIndexed(state, scope);
                DiffIndexService service = state.service;
                URI uri = URI.create("http://stdio.local" + path);
                String route = uri.getRawPath();
                Map<String, String> query = query(uri.getRawQuery());
                Object value;
                int status = 200;
                if ((API_ROOT + "/search").equals(route)) {
                    value = service.search(
                            query.get("agent"), query.get("projectDirectory"), query.get("filePath"),
                            query.get("contentQuery"), query.get("source"), query.get("since"),
                            query.get("until"), integer(query.get("limit")), query.get("sortBy"),
                            query.get("sortDir"));
                } else if ((API_ROOT + "/projects").equals(route)) {
                    value = service.listProjects();
                } else if ((API_ROOT + "/agents").equals(route)) {
                    value = service.listAgents();
                } else if ((API_ROOT + "/sessions").equals(route)) {
                    value = service.listSessions();
                } else if ((API_ROOT + "/stats").equals(route)) {
                    value = service.getStats();
                } else if (route.startsWith(API_ROOT + "/entries/")) {
                    String id = decode(route.substring((API_ROOT + "/entries/").length()));
                    DiffIndexEntry entry = service.get(id);
                    value = entry == null ? Map.of("error", "Diff entry not found: " + id) : entry;
                    status = entry == null ? 404 : 200;
                } else if (route.startsWith(API_ROOT + "/sessions/")) {
                    String sessionId = decode(route.substring((API_ROOT + "/sessions/").length()));
                    value = service.sessionEntries(sessionId);
                } else {
                    value = Map.of("error", "Unknown local diff-index route: " + route);
                    status = 404;
                }
                return new BackendResponse(status, mapper.writeValueAsString(value), null);
            } catch (Exception e) {
                return BackendResponse.error("Local diff-index request failed: " + e.getMessage());
            }
        }

        private void ensureIndexed(ServiceState state, RequestScope scope) {
            if (state.initialized) {
                return;
            }
            state.service.init();
            Runnable reindex = scope.isGlobal()
                    ? state.service::reindexAll
                    : () -> state.service.reindexAll(scope.projectRoot());
            Thread refresh = new Thread(reindex,
                    "kompile-diff-index-refresh-" + scope.cacheKey().replaceAll("[^A-Za-z0-9._-]", "-"));
            refresh.setDaemon(true);
            refresh.start();
            state.initialized = true;
        }

        private static Map<String, String> query(String rawQuery) {
            Map<String, String> values = new HashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return values;
            }
            for (String pair : rawQuery.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                values.put(decode(key), decode(value));
            }
            return values;
        }

        private static Integer integer(String value) {
            return value == null || value.isBlank() ? null : Integer.valueOf(value);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private static final class ServiceState {
            private final DiffIndexService service;
            private boolean initialized;

            private ServiceState(DiffIndexService service) {
                this.service = Objects.requireNonNull(service, "service");
            }
        }
    }

    interface BackendGateway {
        boolean isAvailable(String path);

        default boolean isLocal() {
            return false;
        }

        BackendResponse get(String path, Duration timeout, RequestScope scope) throws Exception;
    }

    record RequestScope(String name, Path projectRoot) {
        static RequestScope project(Path projectRoot) {
            return new RequestScope("project", projectRoot.toAbsolutePath().normalize());
        }

        static RequestScope global() {
            return new RequestScope("global", null);
        }

        static RequestScope remote() {
            return new RequestScope("remote", null);
        }

        boolean isGlobal() {
            return "global".equals(name);
        }

        String cacheKey() {
            return isGlobal() ? "global" : projectRoot.toString();
        }

        void addMetadata(Map<String, Object> metadata) {
            metadata.put("scope", name);
            if (projectRoot != null) {
                metadata.put("project_root", projectRoot.toString());
            }
        }
    }

    record BackendResponse(int statusCode, String body, String error) {
        static BackendResponse error(String message) {
            return new BackendResponse(0, null, message);
        }
    }
}
