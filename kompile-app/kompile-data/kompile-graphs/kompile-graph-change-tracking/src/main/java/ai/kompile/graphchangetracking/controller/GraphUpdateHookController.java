package ai.kompile.graphchangetracking.controller;

import ai.kompile.graphchangetracking.hook.GraphUpdateHook;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph/hooks")
public class GraphUpdateHookController {

    private final GraphUpdateHookRegistry hookRegistry;

    public GraphUpdateHookController(GraphUpdateHookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listHooks() {
        List<Map<String, Object>> hooks = hookRegistry.getAll().stream()
                .map(hook -> Map.<String, Object>of(
                        "id", hook.getId(),
                        "priority", hook.getPriority(),
                        "class", hook.getClass().getSimpleName()
                ))
                .toList();
        return ResponseEntity.ok(hooks);
    }

    @DeleteMapping("/{hookId}")
    public ResponseEntity<Map<String, String>> unregisterHook(@PathVariable String hookId) {
        if (hookRegistry.get(hookId).isPresent()) {
            hookRegistry.unregister(hookId);
            return ResponseEntity.ok(Map.of("hookId", hookId, "message", "Unregistered"));
        }
        return ResponseEntity.notFound().build();
    }
}
