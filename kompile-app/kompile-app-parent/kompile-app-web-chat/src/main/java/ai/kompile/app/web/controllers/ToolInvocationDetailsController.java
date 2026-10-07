package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.metrics.ToolInvocationDetails;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

/** Paged reads from exact invocation content; never scans the global tool catalog. */
@RestController
public class ToolInvocationDetailsController {
    @GetMapping("/api/agents/chat/tool-details")
    public ObjectNode page(@RequestParam String sessionId, @RequestParam String invocationId,
                           @RequestParam String field, @RequestParam(defaultValue = "0") long offset) {
        try {
            return new ToolInvocationDetails(KompileHome.homeDirectory().toPath().resolve("conversations/tool-calls"))
                    .page(sessionId, invocationId, field, offset);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invocation content could not be read", e);
        }
    }
}
