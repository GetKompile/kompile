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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores peer definitions under {@code ~/.kompile/sync/peers/<name>.json} and
 * per-peer baselines under {@code ~/.kompile/sync/state/<peer>-<scope>.json}.
 *
 * <p>Peer records hold the user@host connection string, optional port, and the
 * protocol capabilities returned by the peer's sync serve endpoint. No secrets
 * are stored; authentication relies on the user's existing SSH setup.</p>
 */
public final class SyncPeerStore {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path peersDir;
    private final Path stateDir;

    public SyncPeerStore(Path kompileHome) {
        this.peersDir = kompileHome.resolve("sync").resolve("peers");
        this.stateDir = kompileHome.resolve("sync").resolve("state");
    }

    public Path peersDir() {
        return peersDir;
    }

    public Path stateDir() {
        return stateDir;
    }

    /** Lists saved peer names, sorted. */
    public List<String> listPeers() throws IOException {
        ensureDirs();
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(peersDir)) {
            try (var stream = Files.list(peersDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                        .sorted()
                        .forEach(names::add);
            }
        }
        return names;
    }

    /** Loads a peer record; empty when absent. */
    public Optional<ObjectNode> loadPeer(String name) throws IOException {
        validateName(name);
        Path file = peersDir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of((ObjectNode) MAPPER.readTree(file.toFile()));
    }

    /** Saves a peer record atomically. */
    public void savePeer(String name, ObjectNode peer) throws IOException {
        validateName(name);
        ensureDirs();
        atomicWrite(peersDir.resolve(name + ".json"), MAPPER.writeValueAsBytes(peer));
    }

    /** Deletes a peer record and all baselines associated with it. */
    public boolean deletePeer(String name) throws IOException {
        validateName(name);
        Path file = peersDir.resolve(name + ".json");
        boolean deleted = Files.deleteIfExists(file);
        if (Files.isDirectory(stateDir)) {
            List<Path> matches = new ArrayList<>();
            try (var stream = Files.list(stateDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith(name + "-"))
                        .forEach(matches::add);
            }
            for (Path p : matches) {
                Files.deleteIfExists(p);
            }
        }
        return deleted;
    }

    /** Loads the last-sync baseline (packagePath → hash) for a peer+scope. */
    public Map<String, String> loadBaseline(String peer, SyncScope scope) throws IOException {
        validateName(peer);
        Path file = baselineFile(peer, scope);
        if (!Files.isRegularFile(file)) {
            return new HashMap<>();
        }
        Map<String, String> map = new HashMap<>();
        JsonNode root = MAPPER.readTree(file.toFile());
        JsonNode hashes = root.get("hashes");
        if (hashes != null && hashes.isObject()) {
            hashes.properties().forEach(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    /** Reject baseline reuse across different local/remote profile mounts. */
    void validateBaselineMounts(String peer, SyncScope scope, String identity) throws IOException {
        Path file = baselineFile(peer, scope);
        if (!Files.isRegularFile(file)) return;
        JsonNode root = MAPPER.readTree(file.toFile());
        String previous = root.path("meta").path("mountIdentity").asText("");
        boolean legacyHarness = loadBaseline(peer, scope).keySet().stream()
                .anyMatch(k -> k.startsWith(SyncCatalog.HARNESS_SETTINGS + "/")
                        || k.startsWith(SyncCatalog.HARNESS_CREDENTIALS + "/"));
        if ((!previous.isEmpty() && !previous.equals(identity)) || (previous.isEmpty() && legacyHarness)) {
            throw new IOException("Sync profile mounts differ from this peer's baseline; nothing was transferred. "
                    + "Register a separate peer name for different profiles.");
        }
    }

    /** Persists the post-sync baseline for a peer+scope. */
    public void saveBaseline(String peer, SyncScope scope, Map<String, String> baseline,
                             Map<String, String> meta) throws IOException {
        validateName(peer);
        ensureDirs();
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode metaNode = root.putObject("meta");
        meta.forEach(metaNode::put);
        ObjectNode hashes = root.putObject("hashes");
        baseline.forEach(hashes::put);
        atomicWrite(baselineFile(peer, scope), MAPPER.writeValueAsBytes(root));
    }

    /** Deletes the baseline for a peer+scope (used by reset). */
    public void deleteBaseline(String peer, SyncScope scope) throws IOException {
        validateName(peer);
        Files.deleteIfExists(baselineFile(peer, scope));
    }

    private Path baselineFile(String peer, SyncScope scope) {
        validateName(peer);
        return stateDir.resolve(peer + "-" + scope.storageKey() + ".json");
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid peer name: " + name);
        }
    }

    private void ensureDirs() throws IOException {
        Files.createDirectories(peersDir);
        Files.createDirectories(stateDir);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
