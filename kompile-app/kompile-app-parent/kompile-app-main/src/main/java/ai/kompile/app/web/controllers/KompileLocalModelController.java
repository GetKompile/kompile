package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.KompileLocalModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for managing the Kompile Local Model agent connection.
 * Allows discovering, connecting to, and disconnecting from a local
 * kompile-model-staging instance that serves models via OpenAI-compatible API.
 */
@RestController
@RequestMapping("/api/agents/kompile-local")
@CrossOrigin(origins = "*")
public class KompileLocalModelController {

    private final KompileLocalModelService localModelService;

    public KompileLocalModelController(KompileLocalModelService localModelService) {
        this.localModelService = localModelService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(localModelService.getStatus());
    }

    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover() {
        return ResponseEntity.ok(localModelService.discoverAndRegister());
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody Map<String, String> body) {
        String stagingUrl = body.get("stagingUrl");
        if (stagingUrl == null || stagingUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "stagingUrl is required"));
        }
        return ResponseEntity.ok(localModelService.connectTo(stagingUrl));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        localModelService.disconnect();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Disconnected"));
    }
}
