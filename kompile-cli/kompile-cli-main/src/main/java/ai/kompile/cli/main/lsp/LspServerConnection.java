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

package ai.kompile.cli.main.lsp;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A single live language server bound to one {@code (root, language)} pair. Owns the
 * child process (production) or a pair of streams (test seam), the lsp4j launcher, the
 * open-document set, and the diagnostics inbox.
 *
 * <p>All request and document-sync methods are synchronized around the open-doc map so
 * concurrent tool calls against the same server are serialized safely.</p>
 */
public class LspServerConnection {

    public enum State { STARTING, READY, CRASHED, STOPPED }

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int MAX_OPEN_DOCS = 32;

    private final LspServerConfig cfg;
    private final Path root;
    private final Path logFile;
    private final Process process;                 // null for the test seam
    private final KompileLanguageClient client;
    private final Object logLock = new Object();
    private final long startedAt = System.currentTimeMillis();

    private volatile LanguageServer server;
    private volatile Future<Void> listening;
    private volatile ServerCapabilities capabilities;
    private volatile State state = State.STARTING;
    private volatile String lastError;
    private volatile long lastUsed = System.currentTimeMillis();
    private Thread stderrDrain;

    private final LinkedHashMap<String, OpenDoc> openDocs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OpenDoc> eldest) {
            if (size() > MAX_OPEN_DOCS) {
                sendDidClose(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    // ── Construction ─────────────────────────────────────────────────────────

    /** Production: spawn the server process and wire the launcher. Call {@link #initialize()} next. */
    public LspServerConnection(LspServerConfig cfg, Path root, Path logFile) throws IOException {
        this.cfg = cfg;
        this.root = root.toAbsolutePath().normalize();
        this.logFile = logFile;
        this.process = spawn(cfg, this.root);
        this.client = new KompileLanguageClient(this);
        this.stderrDrain = startStderrDrain(process.getErrorStream());
        wire(process.getInputStream(), process.getOutputStream());
    }

    /** Test seam: wire the launcher over the given streams; no process is spawned. */
    public LspServerConnection(LspServerConfig cfg, Path root, InputStream in, OutputStream out) {
        this.cfg = cfg;
        this.root = root.toAbsolutePath().normalize();
        this.logFile = null;
        this.process = null;
        this.client = new KompileLanguageClient(this);
        wire(in, out);
    }

    private void wire(InputStream in, OutputStream out) {
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, in, out);
        this.listening = launcher.startListening();
        this.server = launcher.getRemoteProxy();
    }

    private static Process spawn(LspServerConfig cfg, Path root) throws IOException {
        List<String> command = expandCommand(cfg, root);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        if (!cfg.env().isEmpty()) {
            pb.environment().putAll(cfg.env());
        }
        pb.redirectErrorStream(false);
        return pb.start();
    }

    private static List<String> expandCommand(LspServerConfig cfg, Path root) {
        if (!cfg.usesDataDir()) {
            return cfg.command();
        }
        Path dataDir = dataDirFor(cfg, root);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ignore) {
            // jdtls will surface a clearer error if the data dir is unusable
        }
        List<String> expanded = new ArrayList<>(cfg.command().size());
        for (String arg : cfg.command()) {
            expanded.add(arg.replace(LspServerConfig.DATA_DIR_PLACEHOLDER, dataDir.toString()));
        }
        return expanded;
    }

    private static Path dataDirFor(LspServerConfig cfg, Path root) {
        String hash = HashUtils.sha256Hex(root.toString());
        String shortHash = hash.length() >= 12 ? hash.substring(0, 12) : hash;
        return KompileHome.homeDirectory().toPath()
                .resolve("lsp").resolve("data").resolve(cfg.language()).resolve(shortHash);
    }

    // ── Initialization handshake ─────────────────────────────────────────────

    /** Perform the {@code initialize}/{@code initialized} handshake; blocks up to the startup timeout. */
    public synchronized void initialize() {
        try {
            InitializeParams params = new InitializeParams();
            if (process != null) {
                params.setProcessId((int) ProcessHandle.current().pid());
            }
            String rootUri = root.toUri().toString();
            params.setRootUri(rootUri);
            Path name = root.getFileName();
            params.setWorkspaceFolders(List.of(
                    new WorkspaceFolder(rootUri, name != null ? name.toString() : root.toString())));
            params.setClientInfo(new ClientInfo("kompile-cli", clientVersion()));
            params.setCapabilities(buildClientCapabilities());
            if (cfg.initializationOptions() != null) {
                params.setInitializationOptions(MAPPER.convertValue(cfg.initializationOptions(), Map.class));
            }
            InitializeResult result = server.initialize(params)
                    .get(cfg.startupTimeoutMs(), TimeUnit.MILLISECONDS);
            this.capabilities = result.getCapabilities();
            server.initialized(new InitializedParams());
            this.state = State.READY;
        } catch (Exception e) {
            this.state = State.CRASHED;
            this.lastError = rootCause(e);
            throw new LspException("LSP initialize failed for " + cfg.language() + ": " + rootCause(e), e);
        }
    }

    private static ClientCapabilities buildClientCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities());
        DocumentSymbolCapabilities documentSymbol = new DocumentSymbolCapabilities();
        documentSymbol.setHierarchicalDocumentSymbolSupport(true);
        td.setDocumentSymbol(documentSymbol);
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
        RenameCapabilities rename = new RenameCapabilities();
        rename.setPrepareSupport(true);
        td.setRename(rename);
        td.setDefinition(new DefinitionCapabilities());
        td.setReferences(new ReferencesCapabilities());
        td.setHover(new HoverCapabilities());

        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        WorkspaceEditCapabilities workspaceEdit = new WorkspaceEditCapabilities();
        workspaceEdit.setDocumentChanges(true);
        workspaceEdit.setResourceOperations(List.of(
                ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete));
        ws.setWorkspaceEdit(workspaceEdit);
        ws.setSymbol(new SymbolCapabilities());
        ws.setWorkspaceFolders(true);

        ClientCapabilities caps = new ClientCapabilities();
        caps.setTextDocument(td);
        caps.setWorkspace(ws);
        return caps;
    }

    // ── Document synchronization ─────────────────────────────────────────────

    /** Open {@code file} if not already open, or push a full-document change if it changed on disk. */
    public synchronized void ensureSynced(Path file) throws IOException {
        touch();
        ensureAlive();
        String uri = file.toUri().toString();
        String content = Files.readString(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        OpenDoc doc = openDocs.get(uri);
        if (doc == null) {
            String languageId = cfg.languageIdFor(LspLanguages.extensionOf(file));
            textDocuments().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, languageId, 1, content)));
            openDocs.put(uri, new OpenDoc(1, mtime, size));
        } else if (doc.lastMtime != mtime || doc.lastSize != size) {
            pushFullChange(uri, doc, content, mtime, size);
        }
    }

    /** After a rename/apply on disk, force open docs to match the new content. */
    public synchronized void refreshFromDisk(Collection<Path> files) {
        for (Path file : files) {
            String uri = file.toUri().toString();
            OpenDoc doc = openDocs.get(uri);
            if (doc == null) {
                continue;
            }
            try {
                if (Files.exists(file)) {
                    pushFullChange(uri, doc, Files.readString(file),
                            Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                } else {
                    sendDidClose(uri);
                    openDocs.remove(uri);
                }
            } catch (IOException ignore) {
                // best effort — a later ensureSynced will reconcile
            }
        }
    }

    private void pushFullChange(String uri, OpenDoc doc, String content, long mtime, long size) {
        int version = doc.version + 1;
        textDocuments().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(content))));
        doc.version = version;
        doc.lastMtime = mtime;
        doc.lastSize = size;
    }

    private void sendDidClose(String uri) {
        try {
            textDocuments().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        } catch (Exception ignore) {
            // server may already be gone
        }
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    public synchronized List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(Path file) throws Exception {
        ensureSynced(file);
        return textDocuments().documentSymbol(new DocumentSymbolParams(docId(file)))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public synchronized Either<List<? extends Location>, List<? extends LocationLink>> definition(Path file, Position pos)
            throws Exception {
        ensureSynced(file);
        return textDocuments().definition(new DefinitionParams(docId(file), pos))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public synchronized List<? extends Location> references(Path file, Position pos, boolean includeDeclaration)
            throws Exception {
        ensureSynced(file);
        return textDocuments().references(new ReferenceParams(docId(file), pos, new ReferenceContext(includeDeclaration)))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public synchronized Hover hover(Path file, Position pos) throws Exception {
        ensureSynced(file);
        return textDocuments().hover(new HoverParams(docId(file), pos))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public synchronized WorkspaceEdit rename(Path file, Position pos, String newName) throws Exception {
        ensureSynced(file);
        return textDocuments().rename(new RenameParams(docId(file), pos, newName))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** Best-effort prepareRename probe; only runs when the server advertises prepare support. Never throws. */
    public synchronized void prepareRename(Path file, Position pos) {
        if (!supportsPrepareRename()) {
            return;
        }
        try {
            ensureSynced(file);
            Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> ignored =
                    textDocuments().prepareRename(new PrepareRenameParams(docId(file), pos))
                            .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // non-fatal: many servers reject prepareRename off a valid identifier
        }
    }

    public synchronized Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> workspaceSymbol(String query)
            throws Exception {
        touch();
        ensureAlive();
        return workspace().symbol(new WorkspaceSymbolParams(query))
                .get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** Sync {@code file}, then wait up to {@code waitMs} for a diagnostics publish; returns whatever is stored. */
    public List<Diagnostic> awaitDiagnostics(Path file, long waitMs) throws IOException {
        String uri = file.toUri().toString();
        CountDownLatch latch = client.armDiagnostics(uri);
        ensureSynced(file);
        try {
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return client.diagnosticsFor(uri);
    }

    public boolean supportsPrepareRename() {
        if (capabilities == null) {
            return false;
        }
        Either<Boolean, RenameOptions> provider = capabilities.getRenameProvider();
        if (provider != null && provider.isRight() && provider.getRight() != null) {
            return Boolean.TRUE.equals(provider.getRight().getPrepareProvider());
        }
        return false;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public synchronized void stop() {
        try {
            if (server != null && process != null) {
                try {
                    server.shutdown().get(5, TimeUnit.SECONDS);
                } catch (Exception ignore) {
                    // proceed to exit regardless
                }
                try {
                    server.exit();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        } finally {
            if (listening != null) {
                listening.cancel(true);
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            state = State.STOPPED;
        }
    }

    private void ensureAlive() {
        if (process != null && !process.isAlive()) {
            state = State.CRASHED;
            lastError = "server process exited (code " + safeExitValue() + ")";
            throw new LspException(lastError);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public LspServerConfig config() { return cfg; }
    public Path root() { return root; }
    public String language() { return cfg.language(); }
    public State state() { return state; }
    public String lastError() { return lastError; }
    public Path logFile() { return logFile; }
    public long lastUsed() { return lastUsed; }
    public long uptimeMs() { return System.currentTimeMillis() - startedAt; }
    public boolean isAlive() { return process == null || process.isAlive(); }
    public long pid() { return process != null ? process.pid() : -1L; }
    public synchronized int openDocCount() { return openDocs.size(); }
    public int diagnosticsCount() { return client.totalDiagnostics(); }

    private void touch() {
        lastUsed = System.currentTimeMillis();
    }

    private TextDocumentService textDocuments() {
        return server.getTextDocumentService();
    }

    private WorkspaceService workspace() {
        return server.getWorkspaceService();
    }

    private TextDocumentIdentifier docId(Path file) {
        return new TextDocumentIdentifier(file.toUri().toString());
    }

    void appendLog(String line) {
        if (logFile == null) {
            System.err.println("[lsp:" + cfg.language() + "] " + line);
            return;
        }
        synchronized (logLock) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignore) {
                // logging must never break the tool
            }
        }
    }

    private Thread startStderrDrain(InputStream err) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ignore) {
                // stream closed on shutdown
            }
        }, "lsp-stderr-" + cfg.language());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private int safeExitValue() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static String clientVersion() {
        String v = LspServerConnection.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /** Per-open-document sync state. */
    private static final class OpenDoc {
        int version;
        long lastMtime;
        long lastSize;

        OpenDoc(int version, long lastMtime, long lastSize) {
            this.version = version;
            this.lastMtime = lastMtime;
            this.lastSize = lastSize;
        }
    }

    /**
     * Minimal {@link LanguageClient}: captures diagnostics + wakes waiters, drains server
     * messages to the log file, and refuses to let the server push edits or config back.
     */
    static final class KompileLanguageClient implements LanguageClient {
        private final LspServerConnection connection;
        private final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> waiters = new ConcurrentHashMap<>();

        KompileLanguageClient(LspServerConnection connection) {
            this.connection = connection;
        }

        CountDownLatch armDiagnostics(String uri) {
            CountDownLatch latch = new CountDownLatch(1);
            waiters.put(uri, latch);
            return latch;
        }

        List<Diagnostic> diagnosticsFor(String uri) {
            List<Diagnostic> d = diagnostics.get(uri);
            return d != null ? d : List.of();
        }

        int totalDiagnostics() {
            return diagnostics.values().stream().mapToInt(List::size).sum();
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            String uri = params.getUri();
            List<Diagnostic> list = params.getDiagnostics() != null ? params.getDiagnostics() : List.of();
            diagnostics.put(uri, list);
            CountDownLatch latch = waiters.remove(uri);
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void telemetryEvent(Object object) {
            // ignored
        }

        @Override
        public void showMessage(MessageParams params) {
            connection.appendLog("[show:" + params.getType() + "] " + params.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            connection.appendLog("[showRequest] " + params.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
            connection.appendLog("[log:" + params.getType() + "] " + params.getMessage());
        }

        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            List<Object> result = new ArrayList<>();
            if (params != null && params.getItems() != null) {
                for (int i = 0; i < params.getItems().size(); i++) {
                    result.add(null);
                }
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
            // v1 never lets the server push edits — the WorkspaceEditApplier owns all writes.
            return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
