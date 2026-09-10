package ai.kompile.cli.main.lsp;

import com.google.gson.JsonParser;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Canned in-JVM LSP4J protocol, never an installed language server or index. */
@Timeout(10)
class LspCallHierarchyTest {
    @TempDir Path tmp;
    private final Stub stub = new Stub();
    private LspServerConnection conn;
    private Future<Void> listening;
    private PipedInputStream serverIn, clientIn;
    private PipedOutputStream serverOut, clientOut;
    private Path file;

    private void start() throws Exception {
        serverIn = new PipedInputStream(1 << 16);
        clientOut = new PipedOutputStream(serverIn);
        clientIn = new PipedInputStream(1 << 16);
        serverOut = new PipedOutputStream(clientIn);
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(stub, serverIn, serverOut);
        listening = launcher.startListening();
        conn = new LspServerConnection(LspServerConfig.builder("java")
                .extensions(Set.of(".java")).startupTimeoutMs(2000).requestTimeoutMs(2000)
                .build(), tmp, clientIn, clientOut);
        conn.initialize();
        file = tmp.resolve("Calls.java");
        Files.writeString(file, "class Calls { void target() {} }");
    }

    @AfterEach void close() throws Exception {
        if (conn != null) conn.stop();
        if (listening != null) listening.cancel(true);
        for (var stream : new AutoCloseable[]{clientOut, serverOut, serverIn, clientIn}) {
            if (stream != null) stream.close();
        }
    }

    @Test void incomingPreservesOpaqueOverloadIdentityAndExactRanges() throws Exception {
        start();
        var result = conn.callHierarchy(file, new Position(0, 20), "incoming", 20, 2000);
        assertTrue(conn.supportsCallHierarchy());
        assertNotNull(stub.initializeParams.getCapabilities().getTextDocument().getCallHierarchy());
        assertFalse(stub.initializeParams.getCapabilities().getTextDocument().getCallHierarchy().getDynamicRegistration());
        assertEquals(file.toUri().toString(), stub.prepareParams.getTextDocument().getUri());
        assertEquals(new Position(0, 20), stub.prepareParams.getPosition());
        assertPreparedItemPreserved();
        assertEquals(1, stub.incomingRequests);
        assertEquals(0, stub.outgoingRequests);
        assertEquals(2, result.totalCalls());
        assertEquals("target(int)", result.declarations().get(0).getDetail());
        assertEquals(stub.peer.getSelectionRange(), result.calls().get(0).peer().getSelectionRange());
        assertEquals(stub.ranges, result.calls().get(0).fromRanges());
        assertEquals(stub.target.getName(), result.calls().get(0).peer().getName(),
                "same names across files must not be merged");
        assertNotEquals(stub.target.getUri(), result.calls().get(0).peer().getUri());
    }

    @Test void outgoingUsesPreparedItemAndCapsEdgesAndCallSites() throws Exception {
        stub.capabilities.setCallHierarchyProvider(org.eclipse.lsp4j.jsonrpc.messages.Either
                .forRight(new CallHierarchyRegistrationOptions()));
        start();
        var result = conn.callHierarchy(file, new Position(0, 20), "outgoing", 1, 2000);
        assertTrue(conn.supportsCallHierarchy(), "options form of capability must work");
        assertPreparedItemPreserved();
        assertEquals(0, stub.incomingRequests);
        assertEquals(1, stub.outgoingRequests);
        assertEquals(2, result.totalCalls());
        assertEquals(1, result.calls().size());
        assertEquals(2, result.calls().get(0).totalRanges());
        assertEquals(List.of(stub.ranges.get(0)), result.calls().get(0).fromRanges());
    }

    @Test void ambiguousPreparedDeclarationsReturnBoundedChoicesWithoutCalls() throws Exception {
        stub.prepared = List.of(stub.target, stub.peer);
        start();
        var result = conn.callHierarchy(file, new Position(), "incoming", 1, 2000);
        assertEquals(2, result.totalDeclarations());
        assertEquals(1, result.declarations().size());
        assertTrue(result.calls().isEmpty());
        assertEquals(0, stub.incomingRequests + stub.outgoingRequests);
    }

    @Test void nullAndEmptyPrepareNeverBecomeNoCallsEvidence() throws Exception {
        stub.prepared = null;
        start();
        assertEquals(0, conn.callHierarchy(file, new Position(), "incoming", 20, 2000).totalDeclarations());
        stub.prepared = List.of();
        assertEquals(0, conn.callHierarchy(file, new Position(), "outgoing", 20, 2000).totalDeclarations());
        assertEquals(0, stub.incomingRequests + stub.outgoingRequests);
    }

    @Test void nullCallsAreAnEmptyServerResult() throws Exception {
        stub.nullCalls = true;
        start();
        var result = conn.callHierarchy(file, new Position(), "outgoing", 20, 2000);
        assertEquals(1, result.totalDeclarations());
        assertEquals(0, result.totalCalls());
        assertTrue(result.calls().isEmpty());
    }

    @Test void absentAndFalseCapabilitiesFailBeforePrepareOrDocumentSync() throws Exception {
        stub.capabilities = new ServerCapabilities();
        start();
        assertFalse(conn.supportsCallHierarchy());
        assertTrue(assertThrows(LspException.class, () -> conn.callHierarchy(file,
                new Position(), "incoming", 20, 1000)).getMessage().contains("unsupported"));
        assertEquals(0, conn.openDocCount());
        assertNull(stub.prepareParams);
        stub.capabilities.setCallHierarchyProvider(false);
        conn.initialize();
        assertFalse(conn.supportsCallHierarchy());
        assertThrows(LspException.class, () -> conn.callHierarchy(file, new Position(), "outgoing", 20, 1000));
        assertNull(stub.prepareParams);
    }

    @Test void protocolErrorIsNotReportedAsEmptySuccess() throws Exception {
        stub.failPrepare = true;
        start();
        assertTrue(assertThrows(LspException.class, () -> conn.callHierarchy(file, new Position(),
                "incoming", 20, 2000)).getMessage().contains("protocol error"));
        assertEquals(0, stub.incomingRequests + stub.outgoingRequests);
    }

    @Test void stalledPrepareTimesOutWithoutIssuingCalls() throws Exception {
        stub.stallPrepare = true;
        start();
        assertTrue(assertThrows(LspException.class, () -> conn.callHierarchy(file, new Position(),
                "incoming", 20, 50)).getMessage().contains("timed out"));
        assertEquals(0, stub.incomingRequests + stub.outgoingRequests);
    }

    @Test void stalledCallsAlsoTimeOut() throws Exception {
        stub.stallCalls = true;
        start();
        assertTrue(assertThrows(LspException.class, () -> conn.callHierarchy(file, new Position(),
                "outgoing", 20, 500)).getMessage().contains("timed out"));
        assertEquals(1, stub.outgoingRequests);
    }

    @Test void callsProtocolErrorIsNotAnEmptySuccess() throws Exception {
        stub.failCalls = true;
        start();
        assertTrue(assertThrows(LspException.class, () -> conn.callHierarchy(file, new Position(),
                "incoming", 20, 2000)).getMessage().contains("canned calls error"));
    }

    @Test void timeoutCancelsPendingFuture() throws Exception {
        var await = LspServerConnection.class.getDeclaredMethod("awaitCallHierarchy", CompletableFuture.class, long.class);
        await.setAccessible(true);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        var error = assertThrows(InvocationTargetException.class, () -> await.invoke(null, pending,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10)));
        assertInstanceOf(LspException.class, error.getCause());
        assertTrue(pending.isCancelled());
    }

    @Test void invalidBoundsFailBeforeAnyProtocolRequest() throws Exception {
        start();
        assertThrows(IllegalArgumentException.class, () -> conn.callHierarchy(file, new Position(), "both", 20, 1000));
        for (int limit : new int[]{0, 101}) {
            assertThrows(IllegalArgumentException.class, () -> conn.callHierarchy(file, new Position(), "incoming", limit, 1000));
        }
        for (long timeout : new long[]{0, 30001}) {
            assertThrows(IllegalArgumentException.class, () -> conn.callHierarchy(file, new Position(), "incoming", 20, timeout));
        }
        assertNull(stub.prepareParams);
    }

    private void assertPreparedItemPreserved() {
        assertEquals(stub.target.getName(), stub.received.getName());
        assertEquals(stub.target.getKind(), stub.received.getKind());
        assertEquals(stub.target.getUri(), stub.received.getUri());
        assertEquals(stub.target.getRange(), stub.received.getRange());
        assertEquals(stub.target.getSelectionRange(), stub.received.getSelectionRange());
        assertEquals(stub.target.getDetail(), stub.received.getDetail());
        var gson = new com.google.gson.Gson();
        assertEquals(gson.toJsonTree(stub.target.getData()), gson.toJsonTree(stub.received.getData()),
                "opaque JSON must round-trip regardless of Gson's in-memory representation");
    }

    static CallHierarchyItem item(String uri, String detail, int line) {
        var item = new CallHierarchyItem("target", SymbolKind.Method, uri,
                new Range(new Position(line, 0), new Position(line + 3, 1)),
                new Range(new Position(line, 5), new Position(line, 11)));
        item.setDetail(detail);
        item.setData(JsonParser.parseString("{\"overload\":\"" + detail + "\",\"token\":[1,2]}"));
        return item;
    }

    static final class Stub implements LanguageServer, TextDocumentService, WorkspaceService {
        ServerCapabilities capabilities = new ServerCapabilities();
        final CallHierarchyItem target = item("file:///Target.java", "target(int)", 2);
        final CallHierarchyItem peer = item("file:///Other.java", "target(String)", 8);
        final List<Range> ranges = List.of(new Range(new Position(10, 4), new Position(10, 12)),
                new Range(new Position(11, 6), new Position(11, 14)));
        volatile List<CallHierarchyItem> prepared = List.of(target);
        volatile CallHierarchyPrepareParams prepareParams;
        volatile InitializeParams initializeParams;
        volatile CallHierarchyItem received;
        volatile int incomingRequests, outgoingRequests;
        volatile boolean failPrepare, stallPrepare, nullCalls, failCalls, stallCalls;

        Stub() { capabilities.setCallHierarchyProvider(true); }
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            initializeParams = params;
            return CompletableFuture.completedFuture(new InitializeResult(capabilities));
        }
        @Override public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
            prepareParams = params;
            if (failPrepare) return CompletableFuture.failedFuture(new IllegalStateException("canned prepare error"));
            if (stallPrepare) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(prepared);
        }
        @Override public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
            received = params.getItem();
            incomingRequests++;
            if (failCalls) return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                            "canned calls error", null)));
            if (stallCalls) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(nullCalls ? null : List.of(
                    new CallHierarchyIncomingCall(peer, ranges), new CallHierarchyIncomingCall(target, ranges)));
        }
        @Override public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
            received = params.getItem();
            outgoingRequests++;
            if (failCalls) return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                            "canned calls error", null)));
            if (stallCalls) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(nullCalls ? null : List.of(
                    new CallHierarchyOutgoingCall(peer, ranges), new CallHierarchyOutgoingCall(target, ranges)));
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() { }
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) { }
        @Override public void didChange(DidChangeTextDocumentParams params) { }
        @Override public void didClose(DidCloseTextDocumentParams params) { }
        @Override public void didSave(DidSaveTextDocumentParams params) { }
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { }
    }
}
