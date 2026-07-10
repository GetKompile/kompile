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
import ai.kompile.cli.main.codeindex.IndexDatabase;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.lsp.LspException;
import ai.kompile.cli.main.lsp.LspServerConfig;
import ai.kompile.cli.main.lsp.LspServerConnection;
import ai.kompile.cli.main.lsp.LspServerManager;
import ai.kompile.cli.main.lsp.LspServerRegistry;
import ai.kompile.cli.main.lsp.WorkspaceEditApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool exposing language-server operations over the {@link LspServerManager}:
 * go-to-definition, find-references, hover, document/workspace symbols, rename
 * (WorkspaceEdit, dry-run by default), diagnostics, and server lifecycle control.
 *
 * <p>Addressing is either explicit {@code file_path} + {@code line}/{@code column}
 * (1-based, converted to LSP's 0-based positions at the boundary) or a {@code symbol}
 * resolved through the local code index.</p>
 */
public class LspTool implements CliTool {

    private final CoordinationStateManager coordinator;
    private final LspServerManager manager;

    public LspTool() {
        this(null);
    }

    public LspTool(CoordinationStateManager coordinator) {
        this.coordinator = coordinator;
        this.manager = LspServerManager.getInstance();
    }

    @Override
    public String id() {
        return "lsp";
    }

    @Override
    public String permissionKey() {
        return "lsp";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    @Override
    public String compactHint() {
        return "Language-server ops: action=definition|references|hover|symbols|workspace_symbols|rename|diagnostics|servers."
                + " Address by file_path+line+column (1-based) or symbol. rename is dry_run by default.";
    }

    @Override
    public String description() {
        return """
                Analyze and refactor code through external language servers (no grep needed).

                Actions:
                  definition        — go to a symbol's definition
                  references        — find all references to a symbol
                  hover             — type/signature/doc at a position
                  symbols           — document symbol outline for a file
                  workspace_symbols — search symbols across a project (needs query + file_path or language)
                  rename            — rename a symbol project-wide via a WorkspaceEdit (dry_run by default)
                  diagnostics       — compile/lint problems for a file
                  servers           — list | status | start | stop | restart

                Addressing (for definition/references/hover/rename):
                  - explicit: file_path + line + column   (line/column are 1-based)
                  - or:       symbol (+ optional project_id, resolved via the local code index)

                rename never writes unless dry_run=false; a dry run renders a diff preview.
                Supported languages: java (jdtls), c/c++/cuda (clangd), rust (rust-analyzer),
                python (pyright), typescript/javascript (typescript-language-server), go (gopls).
                Missing a server binary yields an install hint. First jdtls start is slow (indexing).""";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Which operation to run.");
        ArrayNode actionEnum = action.putArray("enum");
        for (String a : List.of("definition", "references", "hover", "symbols",
                "workspace_symbols", "rename", "diagnostics", "servers")) {
            actionEnum.add(a);
        }

        strProp(props, "file_path", "Target file (relative to the working directory).");
        intProp(props, "line", "1-based line of the target position.");
        intProp(props, "column", "1-based column of the target position.");
        strProp(props, "symbol", "Symbol name/FQN to address via the local code index (alternative to file_path).");
        strProp(props, "project_id", "Local code-index project id for symbol addressing (default: \"default\").");
        strProp(props, "new_name", "New identifier for a rename.");
        boolProp(props, "dry_run", "For rename: preview only without writing (default: true).");
        strProp(props, "query", "Query string for workspace_symbols.");

        ObjectNode serverAction = props.putObject("server_action");
        serverAction.put("type", "string");
        serverAction.put("description", "For action=servers: list | status | start | stop | restart.");
        ArrayNode saEnum = serverAction.putArray("enum");
        for (String a : List.of("list", "status", "start", "stop", "restart")) {
            saEnum.add(a);
        }

        strProp(props, "language", "Language key for workspace_symbols / server start|stop|restart (e.g. java, go).");
        intProp(props, "timeout_ms", "Per-request timeout override (advisory).");
        intProp(props, "diag_wait_ms", "How long to wait for diagnostics to publish (default: 3000).");
        intProp(props, "max_tokens", "Truncate output to this token budget (0 = unlimited).");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "LSP operation");
        String action = text(params, "action");
        if (action.isEmpty()) {
            return ToolResult.error("action is required "
                    + "(definition|references|hover|symbols|workspace_symbols|rename|diagnostics|servers)");
        }
        manager.setWorkingDirectory(context.getWorkingDirectory());
        int maxTokens = params.path("max_tokens").asInt(0);
        try {
            ToolResult result = switch (action) {
                case "definition" -> doDefinition(params, context);
                case "references" -> doReferences(params, context);
                case "hover" -> doHover(params, context);
                case "symbols" -> doSymbols(params, context);
                case "workspace_symbols" -> doWorkspaceSymbols(params, context);
                case "rename" -> doRename(params, context);
                case "diagnostics" -> doDiagnostics(params, context);
                case "servers" -> doServers(params, context);
                default -> ToolResult.error("unknown action: " + action);
            };
            return truncate(result, maxTokens);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (IllegalStateException e) {
            // missing server binary — message carries the install hint
            return ToolResult.error(e.getMessage());
        } catch (LspException e) {
            return ToolResult.error("LSP error: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("LSP " + action + " failed: " + e.getMessage());
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private ToolResult doDefinition(JsonNode params, ToolContext context) throws Exception {
        Target target = resolveTarget(params, context);
        requirePosition(target, "definition");
        LspServerConnection conn = manager.getOrStart(target.file());
        Either<List<? extends Location>, List<? extends LocationLink>> result =
                conn.definition(target.file(), target.position());
        return renderLocations("Definition", flattenDefinition(result), context);
    }

    private ToolResult doReferences(JsonNode params, ToolContext context) throws Exception {
        Target target = resolveTarget(params, context);
        requirePosition(target, "references");
        LspServerConnection conn = manager.getOrStart(target.file());
        List<? extends Location> refs = conn.references(target.file(), target.position(), true);
        List<Location> locs = refs == null ? List.of() : new ArrayList<>(refs);
        return renderLocations("References", locs, context);
    }

    private ToolResult doHover(JsonNode params, ToolContext context) throws Exception {
        Target target = resolveTarget(params, context);
        requirePosition(target, "hover");
        LspServerConnection conn = manager.getOrStart(target.file());
        Hover hover = conn.hover(target.file(), target.position());
        String text = renderHover(hover);
        return ToolResult.success("Hover", text.isEmpty() ? "No hover information." : text);
    }

    private ToolResult doSymbols(JsonNode params, ToolContext context) throws Exception {
        Path file = resolveFileOnly(params, context);
        LspServerConnection conn = manager.getOrStart(file);
        List<Either<SymbolInformation, DocumentSymbol>> symbols = conn.documentSymbol(file);
        if (symbols == null || symbols.isEmpty()) {
            return ToolResult.success("Symbols", "No symbols found in " + rel(file, context));
        }
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        for (Either<SymbolInformation, DocumentSymbol> either : symbols) {
            if (either.isRight()) {
                renderDocumentSymbol(sb, either.getRight(), 0, count);
            } else if (either.getLeft() != null) {
                renderSymbolInformation(sb, either.getLeft());
                count[0]++;
            }
        }
        return ToolResult.success("Symbols (" + count[0] + ")", sb.toString().stripTrailing(),
                Map.of("count", count[0], "file", rel(file, context)));
    }

    private ToolResult doWorkspaceSymbols(JsonNode params, ToolContext context) throws Exception {
        String query = text(params, "query");
        if (query.isEmpty()) {
            return ToolResult.error("query is required for workspace_symbols");
        }
        LspServerConnection conn = resolveServer(params, context);
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                conn.workspaceSymbol(query);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (result != null && result.isLeft() && result.getLeft() != null) {
            for (SymbolInformation si : result.getLeft()) {
                sb.append(kindName(si.getKind())).append(' ').append(si.getName())
                        .append(" — ").append(locationLabel(si.getLocation(), context));
                appendContainer(sb, si.getContainerName());
                sb.append('\n');
                count++;
            }
        } else if (result != null && result.isRight() && result.getRight() != null) {
            for (WorkspaceSymbol ws : result.getRight()) {
                sb.append(kindName(ws.getKind())).append(' ').append(ws.getName())
                        .append(" — ").append(workspaceSymbolLabel(ws.getLocation(), context));
                appendContainer(sb, ws.getContainerName());
                sb.append('\n');
                count++;
            }
        }
        if (count == 0) {
            return ToolResult.success("Workspace symbols", "No symbols match: " + query);
        }
        return ToolResult.success("Workspace symbols (" + count + ")", sb.toString().stripTrailing(),
                Map.of("count", count, "query", query));
    }

    private ToolResult doRename(JsonNode params, ToolContext context) throws Exception {
        Target target = resolveTarget(params, context);
        requirePosition(target, "rename");
        String newName = text(params, "new_name");
        if (newName.isEmpty()) {
            return ToolResult.error("new_name is required for rename");
        }
        boolean dryRun = params.path("dry_run").asBoolean(true);
        LspServerConnection conn = manager.getOrStart(target.file());
        conn.prepareRename(target.file(), target.position());
        WorkspaceEdit edit = conn.rename(target.file(), target.position(), newName);
        if (isEmptyEdit(edit)) {
            return ToolResult.success("Rename",
                    "No rename edits produced — is the position on a renameable symbol?");
        }
        if (!dryRun) {
            context.checkPermission("edit", "LSP rename to '" + newName + "' in "
                    + rel(target.file(), context));
        }
        String projectId = projectIdFor(params, context);
        WorkspaceEditApplier applier = new WorkspaceEditApplier(context, coordinator);
        WorkspaceEditApplier.PostApplyHook hook = touched -> {
            conn.refreshFromDisk(touched);
            reindex(context.getWorkingDirectory(), projectId);
        };
        WorkspaceEditApplier.Result result = applier.apply(edit, dryRun, hook);
        return ToolResult.success(dryRun ? "Rename (dry run)" : "Rename applied", result.output(),
                Map.of("dryRun", result.dryRun(), "applied", result.applied(),
                        "editCount", result.editCount(), "fileCount", result.fileCount(),
                        "created", result.created(), "renamed", result.renamed(), "deleted", result.deleted()));
    }

    private ToolResult doDiagnostics(JsonNode params, ToolContext context) throws Exception {
        Path file = resolveFileOnly(params, context);
        long waitMs = params.path("diag_wait_ms").asLong(3000);
        LspServerConnection conn = manager.getOrStart(file);
        List<Diagnostic> diagnostics = new ArrayList<>(conn.awaitDiagnostics(file, waitMs));
        if (diagnostics.isEmpty()) {
            return ToolResult.success("Diagnostics", "no diagnostics");
        }
        diagnostics.sort(Comparator.comparingInt(d -> d.getSeverity() != null ? d.getSeverity().getValue() : 99));
        String rel = rel(file, context);
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : diagnostics) {
            Position start = d.getRange().getStart();
            sb.append(severityName(d.getSeverity())).append(' ')
                    .append(rel).append(':').append(start.getLine() + 1).append(':').append(start.getCharacter() + 1);
            String tag = diagnosticTag(d);
            if (!tag.isEmpty()) {
                sb.append(" [").append(tag).append(']');
            }
            sb.append(' ').append(oneLine(d.getMessage())).append('\n');
        }
        return ToolResult.success("Diagnostics (" + diagnostics.size() + ")", sb.toString().stripTrailing(),
                Map.of("count", diagnostics.size(), "file", rel));
    }

    private ToolResult doServers(JsonNode params, ToolContext context) throws Exception {
        String serverAction = text(params, "server_action");
        if (serverAction.isEmpty()) {
            serverAction = "list";
        }
        LspServerRegistry registry = manager.registry();
        return switch (serverAction) {
            case "list" -> renderServerList(registry);
            case "status" -> renderServerStatus();
            case "start", "stop", "restart" -> serverLifecycle(serverAction, params, context, registry);
            default -> ToolResult.error("unknown server_action: " + serverAction + " (list|status|start|stop|restart)");
        };
    }

    private ToolResult renderServerList(LspServerRegistry registry) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LspServerConfig> entry : registry.servers().entrySet()) {
            LspServerConfig cfg = entry.getValue();
            boolean available = registry.isAvailable(cfg);
            sb.append(available ? "✓ " : "✗ ").append(cfg.language());
            if (!cfg.enabled()) {
                sb.append(" (disabled)");
            }
            sb.append("  →  ").append(String.join(" ", cfg.command())).append('\n');
            sb.append("    extensions: ").append(String.join(" ", cfg.extensions())).append('\n');
            if (!available) {
                sb.append("    install: ").append(cfg.installHint()).append('\n');
            }
        }
        return ToolResult.success("Configured language servers", sb.toString().stripTrailing());
    }

    private ToolResult renderServerStatus() {
        List<LspServerManager.ServerStatus> statuses = manager.status();
        if (statuses.isEmpty()) {
            return ToolResult.success("Running servers", "no servers running");
        }
        StringBuilder sb = new StringBuilder();
        for (LspServerManager.ServerStatus s : statuses) {
            sb.append(s.language()).append("  [").append(s.state()).append("] pid=").append(s.pid())
                    .append(" up=").append(s.uptimeMs() / 1000).append("s docs=").append(s.openDocs())
                    .append(" diags=").append(s.diagnostics()).append('\n');
            sb.append("    root: ").append(s.root()).append('\n');
            if (!s.logFile().isEmpty()) {
                sb.append("    log:  ").append(s.logFile()).append('\n');
            }
            if (!s.lastError().isEmpty()) {
                sb.append("    error: ").append(s.lastError()).append('\n');
            }
        }
        return ToolResult.success("Running servers (" + statuses.size() + ")", sb.toString().stripTrailing());
    }

    private ToolResult serverLifecycle(String serverAction, JsonNode params, ToolContext context,
                                       LspServerRegistry registry) throws ToolExecutionException {
        String language = text(params, "language");
        if (language.isEmpty()) {
            return ToolResult.error("language is required for server_action=" + serverAction);
        }
        LspServerConfig cfg = registry.forLanguage(language);
        if (cfg == null) {
            return ToolResult.error("unknown language: " + language);
        }
        Path root = serverRoot(params, context, registry, cfg);
        switch (serverAction) {
            case "start" -> {
                LspServerConnection conn = manager.startServer(language, root);
                return ToolResult.success("Server started",
                        language + " started (pid " + conn.pid() + ") at " + conn.root());
            }
            case "stop" -> {
                boolean stopped = manager.stopServer(language, root);
                return ToolResult.success("Server stopped",
                        stopped ? (language + " stopped") : (language + " was not running at " + root));
            }
            case "restart" -> {
                LspServerConnection conn = manager.restartServer(language, root);
                return ToolResult.success("Server restarted",
                        language + " restarted (pid " + conn.pid() + ") at " + conn.root());
            }
            default -> {
                return ToolResult.error("unknown server_action: " + serverAction);
            }
        }
    }

    // ── Target resolution ────────────────────────────────────────────────────

    private record Target(Path file, Position position) {
    }

    private Target resolveTarget(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = text(params, "file_path");
        if (!filePath.isEmpty()) {
            Path file = context.resolvePath(filePath);
            if (!Files.exists(file)) {
                throw new ToolExecutionException("file not found: " + file);
            }
            Integer line = intOrNull(params, "line");
            Integer column = intOrNull(params, "column");
            Position pos = null;
            if (line != null) {
                int col = column != null ? column : 1;
                pos = new Position(Math.max(0, line - 1), Math.max(0, col - 1));
            }
            return new Target(file, pos);
        }
        String symbol = text(params, "symbol");
        if (!symbol.isEmpty()) {
            return resolveSymbol(symbol, text(params, "project_id"), context);
        }
        throw new ToolExecutionException("provide file_path (+ line/column) or symbol");
    }

    private Path resolveFileOnly(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = text(params, "file_path");
        if (!filePath.isEmpty()) {
            Path file = context.resolvePath(filePath);
            if (!Files.exists(file)) {
                throw new ToolExecutionException("file not found: " + file);
            }
            return file;
        }
        String symbol = text(params, "symbol");
        if (!symbol.isEmpty()) {
            return resolveSymbol(symbol, text(params, "project_id"), context).file();
        }
        throw new ToolExecutionException("provide file_path or symbol");
    }

    private Target resolveSymbol(String symbol, String projectId, ToolContext context) throws ToolExecutionException {
        String project = projectId.isEmpty() ? "default" : projectId;
        IndexDatabase index;
        try {
            index = LocalCodeIndexTool.getCachedDb(project);
        } catch (Exception e) {
            throw new ToolExecutionException("index lookup failed for project " + project + ": " + e.getMessage());
        }
        if (index == null) {
            throw new ToolExecutionException("no local index for project " + project
                    + "; pass file_path/line/column or run local_code_index action='index'");
        }
        List<Map<String, Object>> rows;
        try {
            rows = index.search(symbol, null, 50);
        } catch (Exception e) {
            throw new ToolExecutionException("index search failed: " + e.getMessage());
        }
        Map<String, Object> best = pickBest(rows, symbol);
        if (best == null) {
            throw new ToolExecutionException("symbol not found in index: " + symbol);
        }
        String relPath = str(best, "filePath");
        if (relPath == null) {
            throw new ToolExecutionException("indexed symbol has no file path: " + symbol);
        }
        Path file = context.getWorkingDirectory().resolve(relPath).normalize();
        if (!Files.exists(file)) {
            Path alt = Path.of(relPath);
            if (Files.exists(alt)) {
                file = alt;
            }
        }
        Integer startLine = intVal(best, "startLine");
        int line0 = startLine != null ? Math.max(0, startLine - 1) : 0;
        int[] loc = locateOnLines(file, line0, simpleName(symbol));
        return new Target(file, new Position(loc[0], loc[1]));
    }

    private LspServerConnection resolveServer(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = text(params, "file_path");
        if (!filePath.isEmpty()) {
            Path file = context.resolvePath(filePath);
            return manager.getOrStart(file);
        }
        String language = text(params, "language");
        if (!language.isEmpty()) {
            LspServerRegistry registry = manager.registry();
            LspServerConfig cfg = registry.forLanguage(language);
            if (cfg == null) {
                throw new ToolExecutionException("unknown language: " + language);
            }
            Path wd = context.getWorkingDirectory();
            return manager.getOrStartByLanguage(language, registry.resolveRoot(wd, cfg, wd));
        }
        throw new ToolExecutionException("workspace_symbols requires file_path or language to select a server");
    }

    private Path serverRoot(JsonNode params, ToolContext context, LspServerRegistry registry, LspServerConfig cfg)
            throws ToolExecutionException {
        String filePath = text(params, "file_path");
        Path wd = context.getWorkingDirectory();
        if (!filePath.isEmpty()) {
            Path file = context.resolvePath(filePath);
            return registry.resolveRoot(file, cfg, wd);
        }
        return registry.resolveRoot(wd, cfg, wd);
    }

    // ── Rendering helpers ────────────────────────────────────────────────────

    private List<Location> flattenDefinition(Either<List<? extends Location>, List<? extends LocationLink>> result) {
        List<Location> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        if (result.isLeft() && result.getLeft() != null) {
            out.addAll(result.getLeft());
        } else if (result.isRight() && result.getRight() != null) {
            for (LocationLink link : result.getRight()) {
                Range range = link.getTargetSelectionRange() != null
                        ? link.getTargetSelectionRange() : link.getTargetRange();
                out.add(new Location(link.getTargetUri(), range));
            }
        }
        return out;
    }

    private ToolResult renderLocations(String title, List<Location> locations, ToolContext context) {
        if (locations.isEmpty()) {
            return ToolResult.success(title, "No results.");
        }
        Map<String, List<Location>> byUri = new LinkedHashMap<>();
        for (Location loc : locations) {
            byUri.computeIfAbsent(loc.getUri(), k -> new ArrayList<>()).add(loc);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, List<Location>> entry : byUri.entrySet()) {
            Path file = uriToPath(entry.getKey());
            String rel = rel(file, context);
            for (Location loc : entry.getValue()) {
                Position start = loc.getRange().getStart();
                sb.append(rel).append(':').append(start.getLine() + 1).append(':').append(start.getCharacter() + 1);
                String lineText = lineText(file, start.getLine());
                if (!lineText.isBlank()) {
                    sb.append(" — ").append(lineText.trim());
                }
                sb.append('\n');
                count++;
            }
        }
        return ToolResult.success(title + " (" + count + ")", sb.toString().stripTrailing(),
                Map.of("count", count, "files", byUri.size()));
    }

    private String renderHover(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = hover.getContents();
        if (contents.isRight() && contents.getRight() != null) {
            return nz(contents.getRight().getValue());
        }
        StringBuilder sb = new StringBuilder();
        if (contents.isLeft() && contents.getLeft() != null) {
            for (Either<String, MarkedString> item : contents.getLeft()) {
                if (item.isLeft()) {
                    sb.append(item.getLeft());
                } else if (item.getRight() != null) {
                    sb.append(item.getRight().getValue());
                }
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    private void renderDocumentSymbol(StringBuilder sb, DocumentSymbol symbol, int depth, int[] count) {
        sb.append("  ".repeat(depth)).append(kindName(symbol.getKind())).append(' ').append(symbol.getName());
        Range range = symbol.getRange();
        if (range != null) {
            sb.append("  [").append(range.getStart().getLine() + 1).append('-')
                    .append(range.getEnd().getLine() + 1).append(']');
        }
        sb.append('\n');
        count[0]++;
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                renderDocumentSymbol(sb, child, depth + 1, count);
            }
        }
    }

    private void renderSymbolInformation(StringBuilder sb, SymbolInformation symbol) {
        sb.append(kindName(symbol.getKind())).append(' ').append(symbol.getName());
        if (symbol.getLocation() != null && symbol.getLocation().getRange() != null) {
            sb.append("  [").append(symbol.getLocation().getRange().getStart().getLine() + 1).append(']');
        }
        appendContainer(sb, symbol.getContainerName());
        sb.append('\n');
    }

    private String locationLabel(Location location, ToolContext context) {
        if (location == null) {
            return "?";
        }
        Path file = uriToPath(location.getUri());
        int line = location.getRange() != null ? location.getRange().getStart().getLine() + 1 : 1;
        return rel(file, context) + ":" + line;
    }

    private String workspaceSymbolLabel(Either<Location, WorkspaceSymbolLocation> location, ToolContext context) {
        if (location == null) {
            return "?";
        }
        if (location.isLeft()) {
            return locationLabel(location.getLeft(), context);
        }
        WorkspaceSymbolLocation wsl = location.getRight();
        return wsl != null ? rel(uriToPath(wsl.getUri()), context) : "?";
    }

    private void reindex(Path root, String projectId) {
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(root, projectId, null, null, sink);
        } catch (Exception e) {
            System.err.println("[LSP] incremental reindex after rename failed: " + e.getMessage());
        }
    }

    // ── Small utilities ──────────────────────────────────────────────────────

    private void requirePosition(Target target, String action) throws ToolExecutionException {
        if (target.position() == null) {
            throw new ToolExecutionException(action + " requires line/column (with file_path) or a symbol");
        }
    }

    private static boolean isEmptyEdit(WorkspaceEdit edit) {
        if (edit == null) {
            return true;
        }
        boolean noChanges = edit.getChanges() == null || edit.getChanges().isEmpty();
        boolean noDocChanges = edit.getDocumentChanges() == null || edit.getDocumentChanges().isEmpty();
        return noChanges && noDocChanges;
    }

    private Map<String, Object> pickBest(List<Map<String, Object>> rows, String symbol) {
        Map<String, Object> nameMatch = null;
        for (Map<String, Object> row : rows) {
            if (symbol.equals(str(row, "fullyQualifiedName"))) {
                return row;
            }
            if (nameMatch == null && symbol.equals(str(row, "name"))) {
                nameMatch = row;
            }
        }
        if (nameMatch != null) {
            return nameMatch;
        }
        String simple = simpleName(symbol);
        for (Map<String, Object> row : rows) {
            if (simple.equals(str(row, "name"))) {
                return row;
            }
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int[] locateOnLines(Path file, int line0, String needle) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int delta = 0; delta <= 3; delta++) {
                for (int idx : new int[]{line0 + delta, line0 - delta}) {
                    if (idx < 0 || idx >= lines.size()) {
                        continue;
                    }
                    int col = lines.get(idx).indexOf(needle);
                    if (col >= 0) {
                        return new int[]{idx, col};
                    }
                }
            }
        } catch (Exception ignore) {
            // fall through to the indexed line, column 0
        }
        return new int[]{line0, 0};
    }

    private String lineText(Path file, int line0) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (line0 >= 0 && line0 < lines.size()) {
                return lines.get(line0);
            }
        } catch (Exception ignore) {
            // unreadable — omit the inline text
        }
        return "";
    }

    private String rel(Path file, ToolContext context) {
        try {
            return context.getWorkingDirectory().toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private String projectIdFor(JsonNode params, ToolContext context) {
        String projectId = text(params, "project_id");
        if (!projectId.isEmpty()) {
            return projectId;
        }
        Path name = context.getWorkingDirectory().getFileName();
        return name != null ? name.toString() : "default";
    }

    private ToolResult truncate(ToolResult result, int maxTokens) {
        if (maxTokens <= 0 || result.isError()) {
            return result;
        }
        String truncated = LocalCodeIndexTool.truncateToTokenBudget(result.getOutput(), maxTokens);
        if (truncated.equals(result.getOutput())) {
            return result;
        }
        return new ToolResult(result.getTitle(), truncated, result.getMetadata(), result.isError());
    }

    private static void appendContainer(StringBuilder sb, String container) {
        if (container != null && !container.isBlank()) {
            sb.append(" (").append(container).append(')');
        }
    }

    private static String diagnosticTag(Diagnostic d) {
        List<String> parts = new ArrayList<>();
        if (d.getSource() != null && !d.getSource().isBlank()) {
            parts.add(d.getSource());
        }
        if (d.getCode() != null) {
            if (d.getCode().isLeft() && d.getCode().getLeft() != null) {
                parts.add(d.getCode().getLeft());
            } else if (d.getCode().isRight() && d.getCode().getRight() != null) {
                parts.add(String.valueOf(d.getCode().getRight()));
            }
        }
        return String.join("/", parts);
    }

    private static String severityName(DiagnosticSeverity severity) {
        return severity != null ? severity.name() : "Unknown";
    }

    private static String kindName(SymbolKind kind) {
        return kind != null ? kind.name() : "Symbol";
    }

    private static Path uriToPath(String uri) {
        try {
            if (uri.startsWith("file:")) {
                return Path.of(URI.create(uri));
            }
            return Path.of(uri);
        } catch (RuntimeException e) {
            return Path.of(uri.replaceFirst("^file://", ""));
        }
    }

    private static String simpleName(String symbol) {
        int dot = symbol.lastIndexOf('.');
        int hash = symbol.lastIndexOf('#');
        int cut = Math.max(dot, hash);
        return cut >= 0 && cut < symbol.length() - 1 ? symbol.substring(cut + 1) : symbol;
    }

    private static String oneLine(String message) {
        return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String nz(String value) {
        return value != null ? value : "";
    }

    private static String text(JsonNode params, String key) {
        return params.path(key).asText("").trim();
    }

    private static Integer intOrNull(JsonNode params, String key) {
        JsonNode node = params.get(key);
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static Integer intVal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    // ── Schema helpers ───────────────────────────────────────────────────────

    private static void strProp(ObjectNode props, String name, String desc) {
        ObjectNode node = props.putObject(name);
        node.put("type", "string");
        node.put("description", desc);
    }

    private static void intProp(ObjectNode props, String name, String desc) {
        ObjectNode node = props.putObject(name);
        node.put("type", "integer");
        node.put("description", desc);
    }

    private static void boolProp(ObjectNode props, String name, String desc) {
        ObjectNode node = props.putObject(name);
        node.put("type", "boolean");
        node.put("description", desc);
    }
}
