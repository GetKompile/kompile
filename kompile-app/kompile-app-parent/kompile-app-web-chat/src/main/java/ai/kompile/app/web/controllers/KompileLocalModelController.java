package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.KompileLocalModelService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ServiceEndpointsConfigManager endpointConfigManager;

    @Autowired
    public KompileLocalModelController(KompileLocalModelService localModelService) {
        this(localModelService, ServiceEndpointsConfigManager.shared());
    }

    /** Test seam for an isolated managed-config file. */
    KompileLocalModelController(KompileLocalModelService localModelService,
                                ServiceEndpointsConfigManager endpointConfigManager) {
        this.localModelService = localModelService;
        this.endpointConfigManager = endpointConfigManager;
        this.localModelService.setStagingUrlResolver(
                () -> this.endpointConfigManager.current().effectiveStagingUrl());
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
        try {
            String normalized = ServiceEndpointsConfigManager.requireHttpBaseUrl(
                    ServiceEndpointsConfigManager.STAGING_URL_KEY, stagingUrl);
            endpointConfigManager.update(Map.of(
                    ServiceEndpointsConfigManager.STAGING_URL_KEY, normalized));
            return ResponseEntity.ok(localModelService.connectTo(normalized));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Could not save staging endpoint: " + e.getMessage()));
        }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        localModelService.disconnect();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Disconnected"));
    }
}
