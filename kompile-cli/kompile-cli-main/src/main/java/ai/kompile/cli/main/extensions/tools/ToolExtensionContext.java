package ai.kompile.cli.main.extensions.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;

/** Only construction dependencies shared by the built-in tool families. No execution/session state. */
public record ToolExtensionContext(ObjectMapper objectMapper, String baseUrl, String graphBaseUrl,
                                   String crawlBaseUrl, Path workingDirectory, Surface surface) {
    public enum Surface { CHAT, MCP }

    public ToolExtensionContext {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(surface, "surface");
    }
}
