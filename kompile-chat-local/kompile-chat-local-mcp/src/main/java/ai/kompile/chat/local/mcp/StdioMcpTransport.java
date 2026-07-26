package ai.kompile.chat.local.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Newline-delimited UTF-8 stdio transport for {@link GraphMcpServer}.
 *
 * <p>The supplied output stream is protocol-only. Diagnostics are written to the
 * separate error stream and none of the supplied streams are closed.</p>
 */
public final class StdioMcpTransport {

    private final GraphMcpServer server;

    public StdioMcpTransport(GraphMcpServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public void run(InputStream input, OutputStream output, PrintStream error) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                Optional<String> response = server.handle(line);
                if (response.isPresent()) {
                    writer.write(response.get());
                    writer.write('\n');
                    writer.flush();
                }
            } catch (RuntimeException ex) {
                error.println("[kompile-chat-local-mcp] " + ex.getMessage());
                error.flush();
            }
        }
    }
}
