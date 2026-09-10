package ai.kompile.cli.main.lsp;

import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises startup ownership and the real stop path without an external child or manager lifecycle. */
@Timeout(5)
class LspServerManagerCleanupTest {
    @TempDir Path tmp;

    @Test void initializeFailureStopsUnregisteredChild() throws Exception {
        try (var fixture = new Fixture()) {
            var rejected = new IllegalStateException("initialize rejected");
            when(fixture.server.initialize(any())).thenReturn(CompletableFuture.failedFuture(rejected));

            var failure = assertThrows(LspException.class, this::startConnection);

            assertTrue(failure.getMessage().contains("initialize rejected"));
            assertSame(rejected, failure.getCause().getCause());
            verify(fixture.listening).cancel(true);
            var order = inOrder(fixture.child);
            order.verify(fixture.child).destroy();
            order.verify(fixture.child).waitFor(3, TimeUnit.SECONDS);
            verify(fixture.child, never()).destroyForcibly();
        }
    }

    @Test void initializeTimeoutForcesUnresponsiveChildDown() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.server.initialize(any())).thenReturn(new CompletableFuture<>());
            when(fixture.child.waitFor(3, TimeUnit.SECONDS)).thenReturn(false);

            var failure = assertThrows(LspException.class, this::startConnection);

            assertInstanceOf(TimeoutException.class, failure.getCause());
            verify(fixture.listening).cancel(true);
            var order = inOrder(fixture.child);
            order.verify(fixture.child).destroy();
            order.verify(fixture.child).waitFor(3, TimeUnit.SECONDS);
            order.verify(fixture.child).destroyForcibly();
        }
    }

    @Test void cleanupFailureIsSuppressedOnInitializeFailure() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.server.initialize(any())).thenReturn(
                    CompletableFuture.failedFuture(new IllegalStateException("initialize rejected")));
            var cleanupFailure = new IllegalStateException("destroy failed");
            doThrow(cleanupFailure).when(fixture.child).destroy();

            var failure = assertThrows(LspException.class, this::startConnection);

            assertTrue(failure.getMessage().contains("initialize rejected"));
            assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
            verify(fixture.listening).cancel(true);
        }
    }

    @Test void successfulInitializationRetainsChild() throws Throwable {
        try (var fixture = new Fixture()) {
            LspServerConnection connection = startConnection();
            try {
                assertEquals(LspServerConnection.State.READY, connection.state());
                verify(fixture.child, never()).destroy();
                verify(fixture.child, never()).destroyForcibly();
                verify(fixture.listening, never()).cancel(true);
            } finally {
                connection.stop();
            }
        }
    }

    private LspServerConnection startConnection() throws Throwable {
        // Invoke just the private ownership boundary: no singleton, reaper or shutdown hook.
        var manager = mock(LspServerManager.class);
        var method = LspServerManager.class.getDeclaredMethod("startConnection", LspServerConfig.class, Path.class);
        method.setAccessible(true);
        var cfg = LspServerConfig.builder("java").command(List.of("mock-lsp"))
                .startupTimeoutMs(1).build();
        try {
            return (LspServerConnection) method.invoke(manager, cfg, tmp);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Process child = mock(Process.class);
        final LanguageServer server = mock(LanguageServer.class);
        final Future<Void> listening;
        final MockedConstruction<ProcessBuilder> builders;
        final MockedStatic<LSPLauncher> launchers;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            listening = mock(Future.class);
            Launcher<LanguageServer> launcher = mock(Launcher.class);
            when(launcher.startListening()).thenReturn(listening);
            when(launcher.getRemoteProxy()).thenReturn(server);
            when(server.initialize(any())).thenReturn(
                    CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities())));
            when(server.shutdown()).thenReturn(CompletableFuture.completedFuture(null));
            when(child.getInputStream()).thenReturn(InputStream.nullInputStream());
            when(child.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
            when(child.getErrorStream()).thenReturn(InputStream.nullInputStream());
            when(child.waitFor(3, TimeUnit.SECONDS)).thenReturn(true);
            builders = mockConstruction(ProcessBuilder.class, (builder, context) -> when(builder.start()).thenReturn(child));
            launchers = mockStatic(LSPLauncher.class);
            launchers.when(() -> LSPLauncher.createClientLauncher(any(), any(), any())).thenReturn(launcher);
        }

        @Override public void close() {
            launchers.close();
            builders.close();
        }
    }
}
