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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private final Path scopeRoot;
    private final List<String> allowedComponents;
    private final String hostname;
    private final Map<String, List<SyncEntry>> inventory;
    private final java.util.function.Consumer<String> audit;

    private SyncServeHandler(Path scopeRoot, List<String> allowedComponents, String hostname,
                             Map<String, List<SyncEntry>> inventory,
                             java.util.function.Consumer<String> audit) {
        this.scopeRoot = scopeRoot.toAbsolutePath().normalize();
        this.allowedComponents = allowedComponents;
        this.hostname = hostname;
        this.inventory = inventory;
        this.audit = audit == null ? s -> { } : audit;
    }

    /**
     * Builds a handler for a scope root (kompile home or project {@code .kompile}).
     * The scope is inferred from the root's parent directory name.
     */
    public static SyncServeHandler create(Path scopeRoot, List<String> components,
                                          java.util.function.Consumer<String> audit) throws IOException {
        List<String> allowed = SyncCatalog.validate(components);
        Map<String, List<SyncEntry>> inventory =
                SyncInventoryScanner.scan(scopeRoot, allowed);
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            hostname = "unknown-host";
        }
        return new SyncServeHandler(scopeRoot, allowed, hostname, inventory, audit);
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
                    return inventoryResponse(id);
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
        return response;
    }

    private ObjectNode inventoryResponse(int id) {
        ObjectNode response = SyncProtocol.ok(id);
        response.set("inventory", SyncProtocol.writeInventory(inventory));
        return response;
    }

    private ObjectNode fetch(int id, JsonNode request) throws IOException {
        SyncEntry entry = entryFrom(request);
        Path target = resolveWithinRoot(entry);
        if (!Files.isRegularFile(target)) {
            throw new IOException("not found: " + entry.packagePath());
        }
        byte[] bytes = Files.readAllBytes(target);
        ObjectNode response = SyncProtocol.ok(id);
        response.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
        response.put("sha256", SyncInventoryScanner.sha256(target));
        return response;
    }

    private ObjectNode apply(int id, JsonNode request) throws IOException {
        SyncEntry entry = entryFrom(request);
        String action = request.path("action").asText("");
        Path target = resolveWithinRoot(entry);
        if ("write".equals(action)) {
            byte[] bytes = Base64.getDecoder().decode(request.path("dataBase64").asText());
            String expected = request.path("entry").path("sha256").asText("");
            String actual = SyncSession.sha256(bytes);
            if (!expected.equals(actual)) {
                throw new IOException("payload hash mismatch for " + entry.packagePath());
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            audit.accept("write " + entry.packagePath() + " (" + bytes.length + " bytes)");
        } else if ("delete".equals(action)) {
            if (Files.exists(target)) {
                Files.delete(target);
                audit.accept("delete " + entry.packagePath());
            }
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

    /** Resolves and validates the destination path within the component root. */
    private Path resolveWithinRoot(SyncEntry entry) {
        String componentDir = SyncCatalog.componentDir(scopeKind(), entry.component());
        Path componentRoot = scopeRoot.resolve(componentDir).toAbsolutePath().normalize();
        Path target = entry.toPath(componentRoot).toAbsolutePath().normalize();
        if (!target.startsWith(componentRoot)) {
            throw new IllegalArgumentException("path escapes component root: " + entry.relativePath());
        }
        return target;
    }

    private String scopeKind() {
        return ".kompile".equals(scopeRoot.getParent() == null ? ""
                : scopeRoot.getParent().getFileName().toString())
                ? SyncScope.PROJECT : SyncScope.GLOBAL;
    }

    @Override
    public void close() {
        // No persistent resources; hook for future journaling.
    }
}
