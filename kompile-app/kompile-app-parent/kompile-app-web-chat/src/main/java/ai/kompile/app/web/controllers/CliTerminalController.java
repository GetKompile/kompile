package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.CliTerminalService;
import ai.kompile.cli.common.WebChatContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** A host terminal is privileged: only the loopback CLI handoff may mount usable sessions. */
@RestController
@RequestMapping("/api/agents/chat/terminal")
public class CliTerminalController {
    private final CliTerminalService terminals;
    public CliTerminalController(CliTerminalService terminals) { this.terminals = terminals; }

    @GetMapping public Map<String, Object> capabilities(HttpServletRequest request) {
        try {
            requireAccess(request, false);
            return Map.of("available", true, "detachedTimeoutMinutes", 30);
        } catch (ResponseStatusException unavailable) {
            return Map.of("available", false, "reason", unavailable.getReason());
        }
    }
    @GetMapping("/sessions") public List<CliTerminalService.View> list(HttpServletRequest request) {
        requireAccess(request, false);
        return terminals.list(request.getSession(true).getId());
    }
    @PostMapping("/sessions") public CliTerminalService.View launch(HttpServletRequest request,
                                                                  @RequestBody CliTerminalService.Launch launch) {
        requireAccess(request, true);
        try { return terminals.launch(request.getSession(true).getId(), launch); }
        catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
    }
    @PostMapping("/sessions/{id}/stop") public void stop(HttpServletRequest request, @PathVariable String id) {
        requireAccess(request, true);
        terminals.stop(request.getSession(true).getId(), id);
    }
    @DeleteMapping("/sessions/{id}") public void remove(HttpServletRequest request, @PathVariable String id) {
        requireAccess(request, true);
        terminals.remove(request.getSession(true).getId(), id);
    }
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException error) { return Map.of("message", error.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) { return Map.of("message", error.getMessage()); }

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
    public static void requireAccess(HttpServletRequest request, boolean mutation) {
        try {
            if (WebChatContext.workingDirectory() == null)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Terminal requires a local kompile chat --web launch");
        } catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Web chat launch folder is unavailable", unavailable);
        }
        // Host validation also blocks DNS rebinding. Forwarded headers grant no authority here.
        if (!LOOPBACK.contains(request.getRemoteAddr()) || !LOOPBACK.contains(request.getLocalAddr())
                || !LOOPBACK.contains(request.getServerName().toLowerCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Host terminals are available on loopback only");
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!request.getScheme().equals(uri.getScheme()) || uri.getHost() == null
                        || !request.getServerName().equalsIgnoreCase(uri.getHost()) || request.getServerPort() != port
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || (uri.getPath() != null && !uri.getPath().isEmpty()))
                    throw new IllegalArgumentException("Cross-origin terminal access");
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Same-origin terminal access required");
            }
        }
        if (mutation && !"1".equals(request.getHeader("X-Kompile-Terminal")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Terminal request header required");
    }
}
