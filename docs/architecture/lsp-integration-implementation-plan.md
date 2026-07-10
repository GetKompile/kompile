# LSP Integration Implementation Plan (lsp4j) — Handoff Spec

**Date:** 2026-07-02
**Decision record:** lsp4j chosen (user directive, supersedes the hand-rolled-JSON-RPC recommendation in `docs/architecture/cli-code-index-lsp-audit.md` §7.1). Mainstream languages only: **Java, C/C++ (+CUDA via clangd), Rust, Python, JavaScript/TypeScript, Go**.
**Executor:** a delegated implementation agent. Everything needed is in this document + the referenced files. Read every referenced file before editing it.

---

## 0. Context, constraints, and hard rules

**Goal.** Add a new `lsp` MCP tool to `kompile-cli-main` that manages external language servers over stdio and exposes: go-to-definition, find-references, hover, document/workspace symbols, **rename (WorkspaceEdit, dry-run default)**, and diagnostics. This is the "analyze and update codebases without grep" capability from the audit.

**Hard rules (violations have burned us before):**
1. Work directly on the main tree. **NO git commands of any kind. NO worktrees.** Do not commit; the user handles git.
2. Maven is NOT on PATH: use `/home/agibsonccc/dev-apps/mvn/bin/mvn`.
3. **Read any file before editing it.**
4. In NEW files use normal `import` statements — never inline fully-qualified names. Exception: when editing `McpStdioCommand.java`, match its existing inline-FQCN style.
5. Copy the Apache license header from `EditTool.java` into every new file.
6. Our own debug logging goes to `System.err` (MCP protocol owns stdout). LSP server stderr must be drained to log files, never inherited.
7. If the build fails with phantom "cannot find symbol" on core classes, another session's build may be racing yours — wait 60s and retry before touching code.
8. If Maven fails with "No space left on device", `/tmp` is full of `embedding-subprocess-javacpp-*` leaks: `rm -rf /tmp/embedding-subprocess-javacpp-* /tmp/onnxruntime-java*` (only if no kompile app is running).

**Module:** everything lands in `kompile-cli/kompile-cli-main` (do NOT touch `kompile-cli-common` — it has a stale-.m2 install gotcha).

**Key existing seams (verified, with locations):**
- Tool SPI: `CliTool` (`chat/tools/CliTool.java:26-73`) — `id()`, `description()`, `parameterSchema()`, `permissionKey()`, `execute(JsonNode, ToolContext)`, `mcpAnnotations()`, `compactHint()`.
- `ToolContext` (`chat/tools/ToolContext.java`): `resolvePath()` (paths escaping the working dir require the `external_directory` permission), `checkPermission(key, desc)`, `getWorkingDirectory()`, `isAborted()`.
- `ToolResult.success(title, output, metadataMap)` / `ToolResult.error(msg)`.
- `McpToolAnnotations` (`chat/tools/McpToolAnnotations.java:38-56`): use `WRITE` (rename mutates files).
- Registration sites (all three must be updated):
  1. `mcp/McpStdioCommand.java` `buildToolMap(...)` — add next to the code tools at lines ~1191-1194 (`registerCliTool(tools, new ai.kompile.cli.main.chat.tools.LspTool(coordinator), om, wd)`); the `coordinator` field is in scope (line 1140).
  2. `serve/McpSocketSession.java` ~line 493-495 (same cluster).
  3. `chat/tools/ToolRegistryFactory.java` `create(...)` — after the search tools block (line ~88).
- `DynamicToolManager.java`: add `"lsp"` to `CORE_TOOLS` (lines 54-59, alongside `code_search`/`code_graph`/`local_code_index`) and to the `"search"` group set (line 65-67).
- MCP profiles (`McpStdioCommand.java:75-103`): do NOT modify — `full` includes everything automatically; `explore` is contractually read-only and `lsp` isn't.
- Local index lookup for symbol addressing: `codeindex/IndexDatabase.java:395` `public List<Map<String,Object>> search(String query, String entityType, ...)` — rows carry `rel_path`, `start_line`, `name`, `fqn`, `entity_type`. Index root: `~/.kompile/code-index/<projectId>/`.
- Post-edit re-index precedent: `chat/tools/LocalCodeIndexTool.java` `doReplace` (~line 866) re-indexes touched files after writing — mirror that block after rename-apply.
- Home-dir helper: `ai.kompile.cli.common.KompileHome` (see usage in `BackgroundProcessManager.java:19,33`).
- Tests are JUnit 5 (jupiter + params, see `src/test/java/.../VirtualTerminalAgentConsistencyTest.java`).
- Dependency style: explicit versions in `kompile-cli-main/pom.xml` (e.g. sqlite-jdbc at lines 158-163).

**Build/verify commands:**
- Compile + unit tests: `/home/agibsonccc/dev-apps/mvn/bin/mvn -f /home/agibsonccc/Documents/GitHub/kompile/kompile-cli/kompile-cli-main/pom.xml test -Dtest='Lsp*'` (then the full module `test` once green).
- Native rebuild (deploy step, AFTER JVM-mode is green; needs JDK-21-graal as JAVA_HOME — look in `~/dev-apps` for the GraalVM install): `mvn -f kompile-cli-main/pom.xml package -Pnative -DskipTests` from `kompile-cli/` (~4 min). A running MCP server keeps the old binary in memory — the user must reconnect MCP after deploy; just note it in your final report.

---

## WP-L1 — Dependency + GraalVM native-image config

1. In `kompile-cli-main/pom.xml` add a property `<lsp4j.version>0.23.1</lsp4j.version>` and dependency:
   ```xml
   <dependency>
       <groupId>org.eclipse.lsp4j</groupId>
       <artifactId>org.eclipse.lsp4j</artifactId>
       <version>${lsp4j.version}</version>
   </dependency>
   ```
   (pulls `org.eclipse.lsp4j.jsonrpc` + gson transitively). If 0.23.1 doesn't resolve, fall back to 0.21.2.
2. Add a **provided-scope** GraalVM SDK dep so a Feature class compiles under a plain JDK:
   ```xml
   <dependency>
       <groupId>org.graalvm.sdk</groupId>
       <artifactId>nativeimage</artifactId>
       <version>23.1.2</version>
       <scope>provided</scope>
   </dependency>
   ```
   (If that artifact doesn't resolve, use `org.graalvm.sdk:graal-sdk:23.1.2` provided.)
3. New class `ai.kompile.cli.main.lsp.graal.Lsp4jFeature implements org.graalvm.nativeimage.hosted.Feature`:
   - In `beforeAnalysis(BeforeAnalysisAccess access)`: locate the lsp4j jars on the classpath (`Lsp4jFeature.class.getClassLoader()` resources / `java.class.path` scan for `org.eclipse.lsp4j*.jar`), enumerate all `.class` entries under packages `org/eclipse/lsp4j/` (protocol POJOs + `adapters` + `services` + `jsonrpc/messages` + `jsonrpc/services`), and for each: `Class.forName(name, false, loader)` then `RuntimeReflection.register(clazz)`, `RuntimeReflection.register(clazz.getDeclaredConstructors())`, `...getDeclaredMethods()`, `...getDeclaredFields()`. Swallow per-class failures (log count to System.err at the end). Gson serializes these POJOs reflectively at runtime — bulk registration is deliberate.
4. Wire the feature into the existing `-Pnative` profile in `kompile-cli-main/pom.xml`: find the native-image plugin `<buildArgs>` and add `--features=ai.kompile.cli.main.lsp.graal.Lsp4jFeature`.
5. Edit `src/main/resources/META-INF/native-image/ai.kompile/kompile-cli/proxy-config.json` (read it first; it exists): add entries for lsp4j's dynamic remote proxies:
   ```json
   { "interfaces": ["org.eclipse.lsp4j.services.LanguageServer", "org.eclipse.lsp4j.jsonrpc.Endpoint"] },
   { "interfaces": ["org.eclipse.lsp4j.services.LanguageServer"] },
   { "interfaces": ["org.eclipse.lsp4j.services.LanguageClient", "org.eclipse.lsp4j.jsonrpc.Endpoint"] }
   ```
   If the native binary later throws a missing-proxy error, copy the interface list from the exception message verbatim into this file — that is the expected debugging loop.

**Acceptance:** `mvn ... compile` green under plain JDK; the provided-scope dep leaks nothing into the shaded/runtime artifact.

---

## WP-L2 — `ai.kompile.cli.main.lsp` core: config + registry + language map

New package `ai.kompile.cli.main.lsp`.

**`LspServerConfig`** — immutable config record/POJO: `String language`, `List<String> command`, `Set<String> extensions`, `Map<String,String> languageIds` (extension → LSP languageId), `List<String> rootMarkers`, `boolean enabled`, `long startupTimeoutMs`, `long requestTimeoutMs`, `Map<String,String> env`, `JsonNode initializationOptions` (nullable), `String installHint`.

**`LspLanguages`** — static helpers: extension→language lookup built from the registry; nothing hardcoded outside `LspServerRegistry.builtIns()`.

**`LspServerRegistry`** — constructor takes an explicit config-file `Path` (tests inject a temp file); production callers use `KompileHome`-derived `~/.kompile/lsp-servers.json`. Behavior:
- `builtIns()` returns the table below.
- If the JSON file exists: `{"servers": {"<language>": {<partial overrides>}}}` — per-language deep-ish merge (any present key replaces the built-in value; `"enabled": false` disables).
- `resolveForFile(Path file)` → config by extension; `resolveRoot(Path file, LspServerConfig cfg)` → walk parent dirs from the file up to (and stopping at) the user home or filesystem root, first dir containing any `rootMarkers` entry wins; fallback = the tool's working directory.
- `isAvailable(cfg)` → `command.get(0)` is an existing executable file if absolute, else found+executable in any `PATH` entry.

**Built-in defaults (the deliverable language set):**

| language | command | extensions | languageIds | rootMarkers | startupTimeout | installHint |
|---|---|---|---|---|---|---|
| `java` | `jdtls -data {dataDir}` | `.java` | `.java→java` | `pom.xml`, `build.gradle`, `build.gradle.kts`, `.git` | 90s | "install Eclipse JDT LS; ensure the `jdtls` launcher is on PATH (needs a JDK 17+)" |
| `cpp` | `clangd --background-index --log=error` | `.c .h .cpp .hpp .cc .hh .cxx .hxx .cu .cuh` | `.c→c`, `.h/.hpp/…→cpp`, **`.cu/.cuh→cuda-cpp`** | `compile_commands.json`, `.clangd`, `.git` | 30s | "install clangd (apt/dnf `clangd` or LLVM release); CUDA needs compile flags in compile_commands.json" |
| `rust` | `rust-analyzer` | `.rs` | `rust` | `Cargo.toml`, `.git` | 45s | "`rustup component add rust-analyzer`" |
| `python` | `pyright-langserver --stdio` | `.py .pyi` | `python` | `pyproject.toml`, `setup.py`, `requirements.txt`, `.git` | 30s | "`npm i -g pyright`" |
| `typescript` | `typescript-language-server --stdio` | `.ts .tsx .js .jsx .mjs .cjs` | `.ts→typescript`, `.tsx→typescriptreact`, `.js/.mjs/.cjs→javascript`, `.jsx→javascriptreact` | `tsconfig.json`, `package.json`, `.git` | 30s | "`npm i -g typescript-language-server typescript`" |
| `go` | `gopls` | `.go` | `go` | `go.mod`, `.git` | 30s | "`go install golang.org/x/tools/gopls@latest`" |

`{dataDir}` placeholder (jdtls only): expand to `~/.kompile/lsp/data/java/<first-12-hex-of-sha256(rootPath)>`, created on demand. CUDA note: clangd treats `.cu/.cuh` as C++-family; real CUDA fidelity requires the project's `compile_commands.json` to carry the nvcc/clang CUDA flags — document, don't solve.

**Acceptance:** unit tests — builtin resolution by extension, JSON overlay merge, `enabled:false`, root-marker walk (temp dir tree), availability probe against a fake `PATH`.

---

## WP-L3 — `LspServerConnection` (one live server)

One instance per (root, language). Two constructors: production `(LspServerConfig cfg, Path root, Path logFile)` spawns the process; **test seam** `(LspServerConfig cfg, Path root, InputStream in, OutputStream out)` skips the process (used by the in-JVM round-trip test).

Production start sequence:
1. `ProcessBuilder(cfg.command)` with `{dataDir}` expanded, `directory(root)`, `environment().putAll(cfg.env)`, `redirectErrorStream(false)`. Start; spawn a daemon thread draining stderr → append to `logFile` (`~/.kompile/logs/lsp/<language>-<rootHash>.log`, dir created on demand — consistent with the existing `~/.kompile/logs` aggregation convention).
2. lsp4j wiring:
   ```java
   Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
           client, process.getInputStream(), process.getOutputStream());
   listening = launcher.startListening();
   server = launcher.getRemoteProxy();
   ```
   where `client` is an inner `KompileLanguageClient implements LanguageClient`:
   - `publishDiagnostics(p)` → store `p.getDiagnostics()` in `ConcurrentHashMap<String uri, List<Diagnostic>>` + bump a per-uri `CompletableFuture`/latch so waiters wake.
   - `logMessage`/`showMessage` → append to the same log file; `telemetryEvent` → ignore;
   - `showMessageRequest` → `CompletableFuture.completedFuture(null)`;
   - `configuration(params)` → completed future of a list of `null`s sized to `params.getItems()` (jdt.ls/pyright poll this);
   - `applyEdit` → `completedFuture(new ApplyWorkspaceEditResponse(false))` (we never let servers push edits in v1);
   - `registerCapability`/`unregisterCapability` → `completedFuture(null)`.
3. `initialize`: `InitializeParams` with `processId`, `rootUri = root.toUri().toString()`, `workspaceFolders = [new WorkspaceFolder(rootUri, root.getFileName())]`, `clientInfo = new ClientInfo("kompile-cli", <version or "dev">)`, `initializationOptions` (convert the Jackson `JsonNode` to a plain `Map` via `ObjectMapper.convertValue(node, Map.class)` — lsp4j/gson serializes maps fine), and capabilities:
   - textDocument: `synchronization` (defaults), `documentSymbol.hierarchicalDocumentSymbolSupport=true`, `publishDiagnostics` (default caps object), `rename.prepareSupport=true`, plus default `definition`/`references`/`hover` capability objects.
   - workspace: `workspaceEdit.documentChanges=true`, `workspaceEdit.resourceOperations=[create,rename,delete]`, `symbol` (default), `workspaceFolders=true`.
   `initialize().get(cfg.startupTimeoutMs, MILLISECONDS)` → keep the returned `ServerCapabilities`; then `server.initialized(new InitializedParams())`. Verify exact setter names against the resolved lsp4j version — they occasionally shift between minors; adapt mechanically, don't redesign.
4. Document sync (all methods on the connection, synchronized around the doc map):
   - `ensureSynced(Path file)`: track `{version, lastMtime, lastSize}` per open uri. Not open → `didOpen(new TextDocumentItem(uri, languageIdFor(file), 1, Files.readString(file)))`. Open but disk changed → `didChange` with a **range-less** `TextDocumentContentChangeEvent(fullText)` and incremented version (a range-less change is a full-document replace and is legal regardless of the negotiated sync kind). LRU cap 32 open docs — evict oldest via `didClose`.
   - `refreshFromDisk(Collection<Path>)` — called after rename-apply so open docs match the new disk state.
5. Request helpers, each `future.get(cfg.requestTimeoutMs default 15_000)`: `documentSymbol`, `definition`, `references` (with `ReferenceContext(includeDeclaration=true)`), `hover`, `prepareRename` (only if `ServerCapabilities.renameProvider` advertises prepare; treat failures as non-fatal), `rename`, `workspaceSymbol`. Unwrap the `Either`/`Either3` result shapes per the resolved lsp4j version.
6. `awaitDiagnostics(uri, waitMs)`: after `ensureSynced`, wait up to `waitMs` (default 3000) for a publish for that uri; return whatever is stored (possibly empty — that's a valid "no problems" answer).
7. `stop()`: `shutdown().get(5s)` → `exit()` → `process.destroy()`; force after 3s. Track state enum `STARTING/READY/CRASHED/STOPPED` + `lastError`.

**Acceptance:** compiles; exercised end-to-end by WP-L9's in-JVM test (no external servers needed).

---

## WP-L4 — `LspServerManager` (lifecycle)

- Singleton held by `LspTool` via a static lazy holder (so all three registration sites just `new LspTool(...)` without threading lifecycle through them). JVM shutdown hook stops all connections.
- `ConcurrentHashMap<String, LspServerConnection>` keyed `root + "|" + language`; `getOrStart(file)` resolves config+root via the registry, probes availability (missing binary → `IllegalStateException` carrying `installHint`), starts lazily.
- Crash policy: if a request finds the process dead → mark `CRASHED`, restart once automatically; more than 2 restarts within 10 minutes → stay down until an explicit `servers restart`.
- Idle reaper: daemon thread, stop connections idle > 10 minutes (touch a `lastUsed` timestamp on every request). jdt.ls is expensive to restart — use 30 minutes for `java`.
- `status()` snapshot for the tool: language, root, state, pid, uptime, open docs, diagnostics count, log file path, lastError.

---

## WP-L5 — `LspPositions` + symbol addressing

- LSP positions are **0-based line + UTF-16 code-unit column**. Implement and unit-test: `offsetOf(String content, Position)` and `positionOf(String content, int offset)` — iterate code points; a code point above `0xFFFF` counts as TWO UTF-16 units. Include an emoji/surrogate-pair test case; naive `char` indexing is the classic bug here.
- Tool-facing addressing (in `LspTool`): either explicit `file_path` + `line`/`column` (**1-based externally, convert at the boundary**), or `symbol` (+ optional `project_id`): open `~/.kompile/code-index/<projectId or "default">/index.db` via the existing `IndexDatabase.search(symbol, null, ...)` (`IndexDatabase.java:395`), prefer exact `fqn` match then exact `name` match, take `rel_path` + `start_line`, then scan that line (±3 lines tolerance) in the file for the simple name to get the column. No index → actionable error: "no local index for project X; pass file_path/line/column or run local_code_index action='index'".

---

## WP-L6 — `WorkspaceEditApplier`

Input: `WorkspaceEdit`, dry-run flag, `ToolContext` (for `resolvePath`/permissions), optional `CoordinationStateManager`.

Algorithm (two-phase; all-or-nothing):
1. Normalize to an ordered op list: prefer `getDocumentChanges()` when present (ordered `Either<TextDocumentEdit, ResourceOperation>` — handle `CreateFile`/`RenameFile`/`DeleteFile`); else `getChanges()` map.
2. Convert every uri (`file://`) to `Path`; each must pass `context.resolvePath(...)` semantics (outside-working-dir triggers the `external_directory` permission — reuse `ToolContext`).
3. Phase 1 in memory: read each touched file once; apply that file's `TextEdit`s **sorted by start position descending** using `LspPositions.offsetOf`; simulate resource ops. Any failure → error, disk untouched.
4. Dry-run (default): stop here; render a per-file preview — for each file `--- a/<rel> / +++ b/<rel>` with the changed regions as `-`/`+` line blocks (simple line-diff of before/after is fine; exact unified-diff hunk headers not required) + summary `N edits in M files (+created/renamed/deleted counts)`.
5. Apply: back up originals in memory; if a `CoordinationStateManager` was provided, register an edit lock per file before writing and release in `finally` (read `coordination/CoordinationStateManager.java` for the exact register/release API — `EditCoordinatorTool` shows the call pattern); write all files (+`Files.createDirectories` for creates, `Files.move` for renames); on any IO failure restore every backup (best effort) and error out.
6. After apply: mirror the `doReplace` post-edit re-index block from `LocalCodeIndexTool.java:~866` for the touched files, and call `connection.refreshFromDisk(touched)`.

**Acceptance:** unit tests on temp dirs — multi-edit single file (descending-order correctness), multi-file, UTF-16 column edit, RenameFile op, mid-apply failure rolls back (make a target dir read-only to force it), dry-run touches nothing.

---

## WP-L7 — `LspTool` (the MCP tool)

`chat/tools/LspTool.java`, `implements CliTool`. Constructors `()` and `(CoordinationStateManager coordinator)` (mirror `EditTool`). `id()="lsp"`, `permissionKey()="lsp"`, `mcpAnnotations()=WRITE`.

`compactHint()` (≤180 chars, exactly this): `"Language-server ops: action=definition|references|hover|symbols|workspace_symbols|rename|diagnostics|servers. Address by file_path+line+column (1-based) or symbol. rename is dry_run by default."`

Parameter schema (all optional except `action`; enum on `action`):
`action` (definition|references|hover|symbols|workspace_symbols|rename|diagnostics|servers), `file_path`, `line` (int, 1-based), `column` (int, 1-based), `symbol`, `project_id`, `new_name`, `dry_run` (bool, default **true**), `query` (workspace_symbols), `server_action` (list|status|start|stop|restart), `language`, `timeout_ms`, `diag_wait_ms`, `max_tokens`.

Behavior per action (each: resolve target → `manager.getOrStart(file)` → `ensureSynced` → request → render):
- `definition` / `references`: group `Location`s by file; render `path:line:col — <line text trimmed>` (read the target line from disk); metadata `{count, files}`.
- `hover`: render the markdown/plaintext contents raw.
- `symbols`: `documentSymbol` for `file_path`; hierarchical tree with `SymbolKind` names + line ranges.
- `workspace_symbols`: `workspaceSymbol(query)` list.
- `rename`: needs target + `new_name`. `prepareRename` when supported (non-fatal), then `rename` → `WorkspaceEditApplier`. **Before applying (dry_run=false) call `context.checkPermission("edit", "LSP rename ...")` in addition to the tool's own key.** Response = applier preview/summary.
- `diagnostics`: `ensureSynced` then `awaitDiagnostics`; render `severity file:line:col [source/code] message`, sorted by severity; empty list renders "no diagnostics" (that's a pass, not an error).
- `servers`: `list` (configured servers + availability + installHint for missing binaries), `status` (manager snapshot), `start|stop|restart` (needs `language`, optional root via `file_path`).
- Errors from missing binaries must surface the `installHint`. Honor `context.isAborted()` between steps. Apply the `max_tokens` truncation pattern used by `LocalCodeIndexTool` (lines 90-102) to every output.

`description()`: full multi-line description enumerating actions, addressing modes, and the dry-run default (COMPACT strips it — that's what `compactHint` is for, but daemon/full-schema modes still show it).

---

## WP-L8 — Registration + prompts

1. The three registration sites from §0 (read each first; match local style — `McpStdioCommand` uses inline FQCN, pass `coordinator` there; `ToolRegistryFactory`/`McpSocketSession` per their local patterns).
2. `DynamicToolManager.java`: `CORE_TOOLS` + `"search"` group as specified in §0.
3. `src/main/resources/templates/system-prompt.md` line ~36: add `lsp` to the Search/code-intelligence tool line.
4. `src/main/resources/templates/AGENTS.md` (the untruncated doc channel): add an `### lsp` section — action table, addressing modes, 1-based line/column note, dry-run default, per-server install hints, "CUDA via clangd needs compile_commands.json", "jdt.ls first-start is slow (indexing) — early requests may return partial results".
5. Root `/home/agibsonccc/Documents/GitHub/kompile/AGENTS.md`: add the same section (read it first; it's generated-but-live — insert alongside the existing tool sections).

---

## WP-L9 — Tests (JUnit 5, no external binaries required)

Under `src/test/java/ai/kompile/cli/main/lsp/`:
1. `LspServerRegistryTest`, `LspPositionsTest`, `WorkspaceEditApplierTest` — per WP-L2/L5/L6 acceptance lists.
2. `LspConnectionRoundTripTest` — the key one, fully in-JVM:
   - Implement a `StubLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService` returning canned data (`documentSymbol` → one class symbol; `definition` → a fixed `Location`; `rename` → a `WorkspaceEdit` with two `TextEdit`s in one temp file; records `didOpen`/`didChange` versions into fields the test asserts on).
   - Join client and server over two `PipedInputStream`/`PipedOutputStream` pairs: `LSPLauncher.createServerLauncher(stub, clientToServerIn, serverToClientOut)` + the WP-L3 test-seam constructor on the other ends. Both `startListening()`.
   - Assert: initialize handshake completes; `ensureSynced` sends didOpen v1 then didChange v2 after a disk change; `definition` unwraps; `rename` + `WorkspaceEditApplier` (dry_run=false) mutates the temp file correctly; diagnostics latch wakes when the stub pushes `publishDiagnostics`.
3. `LspToolSmokeIT` — real-server smoke, **gated**: `@EnabledIf`-style check that `typescript-language-server`, `clangd`, `rust-analyzer`, or `gopls` is on PATH (test whichever is found against a tiny fixture project in `@TempDir`; skip cleanly otherwise). Never fail CI for a missing binary.

**Acceptance:** `mvn ... test -Dtest='Lsp*'` green, then the FULL module `test` green (no regressions).

---

## WP-L10 — Final verification + report

1. Full module build green (JVM).
2. Native build per §0 (JDK-21-graal). If it fails on lsp4j reflection/proxy: fix via `Lsp4jFeature`/`proxy-config.json` (§WP-L1.5 loop) — do NOT disable the tool to make the build pass.
3. Smoke the native binary directly (it's `kompile-cli-main/target/kompile-cli-main`): run its MCP stdio mode and call `lsp action=servers server_action=list` via a JSON-RPC line, or minimally verify the tool appears in `tools/list`.
4. Report: what was built, test counts, which real servers were smoke-tested, native build result, and the reminder that running MCP sessions must reconnect to pick up the new binary. **Do not run any git commands.**

## Out of scope (do not build)
- Index write-back of LSP results into `entities_meta`/`relations` (`source` column) — Phase 1b, separate spec.
- Build-runner tool / registration `buildCommand` fields (audit Phase 3).
- `code_action`, `formatting`, semantic tokens, call hierarchy — v2.
- Auto-diagnostics hook after every `edit`/`write` — v1 is the explicit `diagnostics` action only.
