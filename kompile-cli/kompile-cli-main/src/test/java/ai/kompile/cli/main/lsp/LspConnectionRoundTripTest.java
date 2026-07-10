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

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full in-JVM round trip of {@link LspServerConnection} against a stub language server
 * joined over piped streams — exercises the lsp4j wiring without any external binary.
 */
class LspConnectionRoundTripTest {

    @TempDir
    Path tmp;

    private StubLanguageServer stub;
    private LspServerConnection conn;
    private Future<Void> serverListening;
    private PipedInputStream serverIn;
    private PipedOutputStream clientOut;
    private PipedInputStream clientIn;
    private PipedOutputStream serverOut;

    @BeforeEach
    void setUp() throws Exception {
        serverIn = new PipedInputStream(1 << 16);
        clientOut = new PipedOutputStream(serverIn);
        clientIn = new PipedInputStream(1 << 16);
        serverOut = new PipedOutputStream(clientIn);

        stub = new StubLanguageServer();
        stub.cannedDefinition = new Location("file:///defined.java",
                new Range(new Position(3, 4), new Position(3, 10)));

        Launcher<LanguageClient> serverLauncher = LSPLauncher.createServerLauncher(stub, serverIn, serverOut);
        serverListening = serverLauncher.startListening();
        // Give the stub the remote client proxy so it can push diagnostics back.
        stub.connect(serverLauncher.getRemoteProxy());

        LspServerConfig cfg = LspServerConfig.builder("java")
                .extensions(java.util.Set.of(".java")).build();
        conn = new LspServerConnection(cfg, tmp, clientIn, clientOut);
        conn.initialize();
    }

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.stop();
        }
        if (serverListening != null) {
            serverListening.cancel(true);
        }
        closeQuietly(clientOut);
        closeQuietly(serverOut);
        closeQuietly(serverIn);
        closeQuietly(clientIn);
    }

    @Test
    void initializeHandshakeCompletes() {
        assertEquals(LspServerConnection.State.READY, conn.state());
    }

    @Test
    void ensureSyncedSendsOpenThenChange() throws Exception {
        Path file = tmp.resolve("Doc.java");
        Files.writeString(file, "class A {}");

        conn.ensureSynced(file);
        assertTrue(awaitTrue(() -> stub.lastOpenVersion == 1, 3000), "didOpen v1 not observed");

        Files.writeString(file, "class A { int extra; }"); // size changes → didChange
        conn.ensureSynced(file);
        assertTrue(awaitTrue(() -> stub.lastChangeVersion == 2, 3000), "didChange v2 not observed");
    }

    @Test
    void definitionUnwraps() throws Exception {
        Path file = tmp.resolve("Doc.java");
        Files.writeString(file, "class A {}");
        Either<List<? extends Location>, List<? extends LocationLink>> result =
                conn.definition(file, new Position(0, 0));
        assertTrue(result.isLeft());
        assertEquals("file:///defined.java", result.getLeft().get(0).getUri());
        assertEquals(3, result.getLeft().get(0).getRange().getStart().getLine());
    }

    @Test
    void renameEditAppliesToDisk() throws Exception {
        Path file = tmp.resolve("Doc.java");
        Files.writeString(file, "foo foo");
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(Map.of(file.toUri().toString(), List.of(
                new TextEdit(new Range(new Position(0, 0), new Position(0, 3)), "bar"),
                new TextEdit(new Range(new Position(0, 4), new Position(0, 7)), "bar"))));
        stub.cannedRename = edit;

        WorkspaceEdit produced = conn.rename(file, new Position(0, 0), "bar");
        WorkspaceEditApplier applier = new WorkspaceEditApplier(context(), null);
        applier.apply(produced, false);

        assertEquals("bar bar", Files.readString(file));
    }

    @Test
    void diagnosticsLatchWakesOnPublish() throws Exception {
        Path file = tmp.resolve("Problem.java");
        Files.writeString(file, "class P {}");
        List<Diagnostic> diagnostics = conn.awaitDiagnostics(file, 3000);
        assertFalse(diagnostics.isEmpty(), "expected the stub's published diagnostic");
        assertEquals("stub problem", diagnostics.get(0).getMessage());
        assertEquals(DiagnosticSeverity.Warning, diagnostics.get(0).getSeverity());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ToolContext context() {
        PermissionService perms = new PermissionService();
        perms.setAutoApproveAll(true);
        return new ToolContext("test", null, perms, tmp, null);
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // teardown best-effort
            }
        }
    }

    /** Canned language server used only by this test. */
    static final class StubLanguageServer
            implements LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {

        volatile LanguageClient client;
        volatile int lastOpenVersion = -1;
        volatile int lastChangeVersion = -1;
        volatile Location cannedDefinition;
        volatile WorkspaceEdit cannedRename;

        @Override
        public void connect(LanguageClient client) {
            this.client = client;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return this;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return this;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            lastOpenVersion = params.getTextDocument().getVersion();
            if (client != null) {
                Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)),
                        "stub problem", DiagnosticSeverity.Warning, "stub");
                client.publishDiagnostics(new PublishDiagnosticsParams(
                        params.getTextDocument().getUri(), List.of(d)));
            }
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            lastChangeVersion = params.getTextDocument().getVersion();
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                DefinitionParams params) {
            Either<List<? extends Location>, List<? extends LocationLink>> either =
                    Either.forLeft(List.of(cannedDefinition));
            return CompletableFuture.completedFuture(either);
        }

        @Override
        public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
            return CompletableFuture.completedFuture(cannedRename);
        }

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        }
    }
}
