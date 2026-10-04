/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Server-side handler for the sync JSON-lines protocol. Serves inventory,
 * fetch, and apply operations for one scope root. This is the only code that
 * mutates the destination side on remote sync; it re-checks the target hash
 * before overwriting and refuses paths outside the component roots.
 */
public final class SyncServeHandler implements AutoCloseable {

    private final SyncPaths paths;
    private final Map<String, String> targetHashes = new java.util.HashMap<>();
    private final List<String> allowedComponents;
    private final String hostname;
    private final Map<String, List<SyncEntry>> inventory;
    private final java.util.function.Consumer<String> audit;

    private SyncServeHandler(SyncPaths paths, List<String> allowedComponents, String hostname,
                             Map<String, List<SyncEntry>> inventory,
                             java.util.function.Consumer<String> audit) {
        this.paths = paths;
        inventory.values().forEach(entries -> entries.forEach(entry -> targetHashes.put(entry.packagePath(), entry.sha256())));
        this.allowedComponents = allowedComponents;
        this.hostname = hostname;
        this.inventory = inventory;
        this.audit = audit == null ? s -> { } : audit;
    }

    /**
     * Builds a handler for a scope root (kompile home or project {@code .kompile}).
     * This convenience factory uses global scope; the CLI passes explicit mounts.
     */
    public static SyncServeHandler create(Path scopeRoot, List<String> components,
                                          java.util.function.Consumer<String> audit) throws IOException {
        return create(SyncPaths.configured(scopeRoot, SyncScope.GLOBAL, null), components, audit);
    }

    static SyncServeHandler create(SyncPaths paths, List<String> components,
                                   java.util.function.Consumer<String> audit) throws IOException {
        List<String> allowed = SyncCatalog.validate(components);
        Map<String, List<SyncEntry>> inventory = SyncInventoryScanner.scan(paths, allowed);
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            hostname = "unknown-host";
        }
        return new SyncServeHandler(paths, allowed, hostname, inventory, audit);
    }

    /** Handles one parsed request; returns the JSON response. */
    public ObjectNode handle(JsonNode request) {
        String op = request.path("op").asText("");
        int id = request.path("id").asInt(0);
        try {
            switch (op) {
                case SyncProtocol.OP_HELLO:
                    return hello(id);
                case SyncProtocol.OP_INVENTORY:
                    return inventoryResponse(id, request);
                case SyncProtocol.OP_FETCH:
                    return fetch(id, request);
                case SyncProtocol.OP_APPLY:
                    return apply(id, request);
                default:
                    return SyncProtocol.error(id, "unsupported op: " + op);
            }
        } catch (Exception e) {
            return SyncProtocol.error(id, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ObjectNode hello(int id) {
        ObjectNode response = SyncProtocol.ok(id);
        response.put("protocol", SyncCatalog.PROTOCOL);
        response.put("version", SyncCatalog.PROTOCOL_VERSION);
        response.put("hostname", hostname);
        response.put("mountIdentity", paths.mountIdentity());
        return response;
    }

    private ObjectNode inventoryResponse(int id, JsonNode request) {
        if (!paths.scope().equals(request.path("scope").asText(paths.scope()))) {
            throw new IllegalArgumentException("Requested scope does not match endpoint scope.");
        }
        Map<String, List<SyncEntry>> selected = new java.util.TreeMap<>();
        if (!request.has("components")) {
            selected.putAll(inventory);
        } else {
            for (JsonNode component : request.path("components")) {
                String name = component.asText();
                if (!allowedComponents.contains(name)) throw new IllegalArgumentException("Component not exposed: " + name);
                selected.put(name, inventory.get(name));
            }
        }
        ObjectNode response = SyncProtocol.ok(id);
        response.set("inventory", SyncProtocol.writeInventory(selected));
        return response;
    }

    private ObjectNode fetch(int id, JsonNode request) throws IOException {
        SyncEntry entry = entryFrom(request);
        byte[] bytes = paths.read(entry);
        ObjectNode response = SyncProtocol.ok(id);
        response.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
        response.put("sha256", SyncSession.sha256(bytes));
        return response;
    }

    private ObjectNode apply(int id, JsonNode request) throws IOException {
        SyncEntry entry = entryFrom(request);
        String action = request.path("action").asText("");
        paths.checkUnchanged(entry, targetHashes.get(entry.packagePath()));
        if ("write".equals(action)) {
            byte[] bytes = Base64.getDecoder().decode(request.path("dataBase64").asText());
            String expected = request.path("entry").path("sha256").asText("");
            String actual = SyncSession.sha256(bytes);
            if (!expected.equals(actual)) {
                throw new IOException("payload hash mismatch for " + entry.packagePath());
            }
            paths.write(entry, bytes);
            targetHashes.put(entry.packagePath(), actual);
            audit.accept("write " + entry.packagePath() + " (" + bytes.length + " bytes)");
        } else if ("delete".equals(action)) {
            paths.delete(entry);
            targetHashes.remove(entry.packagePath());
            audit.accept("delete " + entry.packagePath());
        } else {
            throw new IOException("unsupported apply action: " + action);
        }
        return SyncProtocol.ok(id);
    }

    private SyncEntry entryFrom(JsonNode request) {
        JsonNode node = request.path("entry");
        String component = node.path("component").asText("");
        if (!allowedComponents.contains(component)) {
            throw new IllegalArgumentException("component not allowed by this endpoint: " + component);
        }
        String path = node.path("path").asText("");
        if (node.hasNonNull("deleted") && node.path("deleted").asBoolean()) {
            return SyncEntry.deletion(component, path, null);
        }
        return SyncEntry.file(component, path,
                node.path("sha256").asText(""), node.path("size").asLong(0), null);
    }

    @Override
    public void close() {
        // No persistent resources; hook for future journaling.
    }
}
