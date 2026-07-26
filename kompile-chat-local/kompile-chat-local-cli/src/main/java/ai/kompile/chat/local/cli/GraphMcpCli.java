package ai.kompile.chat.local.cli;

import ai.kompile.chat.local.GraphToolBackend;
import ai.kompile.chat.local.GraphToolBridge;
import ai.kompile.chat.local.mcp.GraphMcpServer;
import ai.kompile.chat.local.mcp.StdioMcpTransport;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone MCP stdio launcher for the local graph reasoning backend.
 *
 * <p>Unlike the chat REPL, this entry point never writes diagnostics or startup
 * summaries to stdout. Clients may start with an empty graph and invoke the
 * {@code graph_load} tool, or preload a graph with {@code --kgraph}.</p>
 */
public final class GraphMcpCli {

    private GraphMcpCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, InputStream input, OutputStream output, PrintStream error) {
        Path kgraph = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--kgraph" -> {
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--kgraph requires a path");
                        }
                        kgraph = Path.of(args[i]);
                    }
                    case "--help", "-h" -> {
                        printUsage(error);
                        return 0;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
        } catch (RuntimeException ex) {
            error.println("kompile-chat-local-mcp: " + ex.getMessage());
            printUsage(error);
            return 2;
        }

        if (kgraph != null && !Files.isRegularFile(kgraph)) {
            error.println("kompile-chat-local-mcp: requested .kgraph file does not exist: " + kgraph);
            return 2;
        }

        GraphToolBackend backend = null;
        try {
            backend = kgraph == null ? GraphToolBridge.empty() : GraphToolBridge.open(kgraph);
            GraphMcpServer server;
            try {
                server = new GraphMcpServer(backend);
                backend = null; // ownership transferred to the server
            } catch (RuntimeException ex) {
                backend.close();
                backend = null;
                throw ex;
            }

            try (server) {
                new StdioMcpTransport(server).run(input, output, error);
            }
            return 0;
        } catch (Exception ex) {
            error.println("kompile-chat-local-mcp: " + ex.getMessage());
            return 1;
        } finally {
            if (backend != null) {
                backend.close();
            }
        }
    }

    private static void printUsage(PrintStream output) {
        output.println("Usage: kompile-chat-local-mcp [--kgraph <path>]");
        output.println("  --kgraph <path>  preload a .kgraph file; otherwise start with an empty graph");
        output.println("  --help           show this help");
    }
}
